package com.dnctf.movingout2.runtime

import android.content.Context
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

internal fun normalizeRuntimeArchiveEntry(name: String, isDirectory: Boolean): String? {
    if (name.isBlank() || name.contains(':') || name.any { it.code < 32 || it.code == 127 }) return null
    var path = name.replace(0x5c.toChar(), '/')
    if (path.startsWith('/')) return null
    // zip(1) commonly emits "./" prefixes for archives created with "zip -r bundle.zip .".
    // Strip only harmless leading "./" segments; traversal segments remain rejected below.
    while (path.startsWith("./")) path = path.substring(2)
    if (isDirectory) path = path.removeSuffix("/")
    if (path.isEmpty()) return if (isDirectory) "" else null
    if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    return path
}

internal fun isSafeRuntimeArchiveEntry(name: String, isDirectory: Boolean): Boolean =
    normalizeRuntimeArchiveEntry(name, isDirectory) != null

data class RuntimeRepositoryInstallResult(val version: String, val files: Int, val bytes: Long)

internal fun latestRuntimePrerelease(releases: JSONArray): JSONObject? {
    val candidates = (0 until releases.length()).mapNotNull { index ->
        releases.optJSONObject(index)?.takeIf { release ->
            if (!release.optBoolean("prerelease", false) ||
                release.optBoolean("draft", false) ||
                !release.optString("tag_name").startsWith("runtime-v") ||
                release.optString("published_at").isBlank()) return@takeIf false
            val assets = release.optJSONArray("assets") ?: return@takeIf false
            val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name") }.toSet()
            names.contains("moving-out-2-runtime-arm64-v8a.zip") &&
                names.contains("moving-out-2-runtime-arm64-v8a.zip.sha256")
        }
    }
    return candidates.maxByOrNull { it.optString("published_at") }
}

/**
 * Installs a runtime published as assets on the repository's latest GitHub Release.
 *
 * Release contract:
 * - moving-out-2-runtime-arm64-v8a.zip
 * - moving-out-2-runtime-arm64-v8a.zip.sha256 (first token is the ZIP SHA-256)
 *
 * The archive must contain runtime-manifest.json at its root. The manifest's per-file
 * hashes and ABI declarations are checked by RuntimeBundleValidator before activation.
 */
class RuntimeRepositoryInstaller(
    context: Context,
    private val repository: String = "dnctf/Moving-Out-2-Android",
    private val validator: RuntimeBundleValidator = RuntimeBundleValidator(),
    private val maxDownloadBytes: Long = 6L * 1024L * 1024L * 1024L,
    private val maxEntries: Int = 20_000,
    private val maxExpandedBytes: Long = 6L * 1024L * 1024L * 1024L,
    private val storageReserveBytes: Long = 384L * 1024L * 1024L,
    private val diagnostics: RuntimeDiagnostics? = null
) {
    private val appContext = context.applicationContext
    private val parent = File(appContext.filesDir, "native_runtime")
    private val target = File(parent, "bundle")
    private val staging = File(parent, "bundle_staging")
    private val backup = File(parent, "bundle_backup")
    private val archive = File(appContext.cacheDir, "runtime-download.zip")

    fun installLatest(onProgress: (String) -> Unit = {}): RuntimeRepositoryInstallResult {
        record(RuntimeDiagnostic.Severity.INFO, "installLatest started; filesDir=${appContext.filesDir.absolutePath}; cacheDir=${appContext.cacheDir.absolutePath}")
        return try {
            installLatestInternal(onProgress)
        } catch (error: Exception) {
            val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
            record(RuntimeDiagnostic.Severity.ERROR, "Runtime install failed: ${error.javaClass.name}: ${error.message ?: "no message"}\n$trace")
            throw error
        }
    }

    private fun installLatestInternal(onProgress: (String) -> Unit): RuntimeRepositoryInstallResult {
        onProgress("Checking latest runtime release…")
        record(RuntimeDiagnostic.Severity.INFO, "Fetching GitHub runtime release metadata: https://api.github.com/repos/$repository/releases?per_page=30")
        // GitHub's /releases/latest endpoint excludes pre-releases. Select the
        // newest published runtime pre-release explicitly so app downloads work.
        val releaseUrl = "https://api.github.com/repos/$repository/releases?per_page=30"
        val releases = JSONArray(readUrl(releaseUrl, 2 * 1024 * 1024))
        val release = latestRuntimePrerelease(releases)
            ?: throw IllegalStateException("No published Moving Out 2 runtime pre-release was found.")
        val version = release.optString("tag_name").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Runtime pre-release has no tag_name.")
        record(RuntimeDiagnostic.Severity.INFO, "Selected complete runtime release $version; published_at=${release.optString("published_at")}")
        val assets = release.optJSONArray("assets") ?: throw IllegalStateException("Release has no assets.")
        val zipName = "moving-out-2-runtime-arm64-v8a.zip"
        val checksumName = "$zipName.sha256"
        var zipUrl: String? = null
        var checksumUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            when (asset.optString("name")) {
                zipName -> zipUrl = asset.optString("browser_download_url")
                checksumName -> checksumUrl = asset.optString("browser_download_url")
            }
        }
        val bundleAsset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name") == zipName }
        val declaredArchiveBytes = bundleAsset?.optLong("size", -1L) ?: -1L
        val bundleUrl = zipUrl ?: throw IllegalStateException(
            "Release $version does not publish $zipName. Runtime bundle has not been published yet."
        )
        if (declaredArchiveBytes <= 0L || declaredArchiveBytes > maxDownloadBytes) {
            throw IllegalStateException("Runtime release reports an invalid or oversized archive size.")
        }
        val freeBeforeDownload = StatFs(appContext.filesDir.absolutePath).availableBytes
        if (freeBeforeDownload < declaredArchiveBytes + storageReserveBytes) {
            throw IllegalStateException(
                "Not enough free storage to download runtime. Need at least " +
                    "${(declaredArchiveBytes + storageReserveBytes) / (1024 * 1024)} MiB free; " +
                    "available ${freeBeforeDownload / (1024 * 1024)} MiB."
            )
        }
        val digestUrl = checksumUrl ?: throw IllegalStateException(
            "Release $version is missing $checksumName; refusing an unverified runtime download."
        )
        record(RuntimeDiagnostic.Severity.INFO, "Runtime asset selected: url=$bundleUrl; declaredBytes=$declaredArchiveBytes; checksumUrl=$digestUrl")

        onProgress("Downloading and verifying SHA-256…")
        val expectedHash = readUrl(digestUrl, 4096).trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
        if (expectedHash == null || !expectedHash.matches(Regex("[a-f0-9]{64}"))) {
            throw IllegalStateException("Release checksum file is malformed.")
        }
        archive.delete()
        try {
            download(bundleUrl, archive, onProgress)
        } catch (error: Exception) {
            archive.delete()
            throw error
        }
        val actualHash = sha256(archive)
        record(RuntimeDiagnostic.Severity.INFO, "Runtime archive downloaded: path=${archive.absolutePath}; bytes=${archive.length()}; expectedSha256=$expectedHash; actualSha256=$actualHash")
        if (actualHash != expectedHash) {
            record(RuntimeDiagnostic.Severity.ERROR, "Runtime SHA-256 mismatch; expected=$expectedHash actual=$actualHash path=${archive.absolutePath}")
            archive.delete()
            throw IllegalStateException("Runtime archive SHA-256 mismatch; download rejected.")
        }

        if (!parent.mkdirs() && !parent.isDirectory) throw IllegalStateException("Cannot create private runtime directory.")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IllegalStateException("Cannot create runtime staging directory.")
        var activated = false
        try {
            onProgress("Extracting runtime bundle safely…")
            val counts = extract(archive, staging)
            record(RuntimeDiagnostic.Severity.INFO, "Runtime ZIP extraction completed: staging=${staging.absolutePath}; entries=${counts.first}; expandedBytes=${counts.second}")
            // ZIP archives do not reliably preserve POSIX executable bits; restore them
            // before validation because the validator deliberately requires executable files.
            listOf("box64/bin/box64", "wine/bin/wine64", "wine/bin/wineserver").forEach { relative ->
                val file = File(staging, relative)
                if (!file.setExecutable(true, false) && !file.canExecute()) {
                    throw IllegalStateException("Unable to mark runtime executable: $relative")
                }
            }
            val report = validator.validate(staging)
            record(if (report.isReady) RuntimeDiagnostic.Severity.INFO else RuntimeDiagnostic.Severity.ERROR,
                "Staging runtime validation: status=${report.status}; root=${staging.absolutePath}; blockers=${report.blockers.joinToString("; ")}")
            if (!report.isReady) throw IllegalStateException(
                "Downloaded runtime failed manifest/ABI validation: " + report.blockers.joinToString("; ")
            )
            val finalCheck = validator.validate(staging)
            if (!finalCheck.isReady) throw IllegalStateException("Runtime failed post-extraction validation.")
            if (target.exists() && !target.renameTo(backup)) throw IllegalStateException("Cannot preserve current runtime bundle.")
            if (!staging.renameTo(target)) throw IllegalStateException("Cannot activate downloaded runtime bundle.")
            activated = true
            val active = validator.validate(target)
            record(if (active.isReady) RuntimeDiagnostic.Severity.INFO else RuntimeDiagnostic.Severity.ERROR,
                "Activated runtime validation: status=${active.status}; root=${target.absolutePath}; blockers=${active.blockers.joinToString("; ")}")
            if (!active.isReady) throw IllegalStateException("Activated runtime failed revalidation.")
            backup.deleteRecursively()
            archive.delete()
            onProgress("Runtime $version installed and verified.")
            return RuntimeRepositoryInstallResult(version, counts.first, counts.second)
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (backup.exists()) {
                if (activated) target.deleteRecursively()
                if (!backup.renameTo(target)) {
                    throw IllegalStateException("Runtime install failed and previous bundle could not be restored.", error)
                }
            } else if (activated) {
                target.deleteRecursively()
            }
            throw error
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun record(severity: RuntimeDiagnostic.Severity, message: String) {
        diagnostics?.record(RuntimeDiagnostic(severity, "runtime-installer", message))
    }

    private fun readUrl(url: String, limit: Int): String {
        val connection = open(url)
        try {
            val responseCode = connection.responseCode
            ensureAllowedGitHubResponse(connection)
            if (responseCode !in 200..299) throw IllegalStateException("Repository request failed: HTTP $responseCode; url=$url")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw IllegalStateException("Repository metadata exceeds size limit.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun download(url: String, destination: File, onProgress: (String) -> Unit) {
        val connection = open(url)
        try {
            val responseCode = connection.responseCode
            ensureAllowedGitHubResponse(connection)
            if (responseCode !in 200..299) throw IllegalStateException("Runtime download failed: HTTP $responseCode; url=$url")
            val declared = connection.contentLengthLong
            if (declared > maxDownloadBytes) throw IllegalStateException("Runtime archive exceeds configured download limit.")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxDownloadBytes) throw IllegalStateException("Runtime download exceeded configured size limit.")
                        output.write(buffer, 0, count)
                        if (declared > 0) onProgress("Downloading runtime: ${(total * 100 / declared).coerceIn(0, 100)}%")
                    }
                }
            }
            if (total == 0L) throw IllegalStateException("Downloaded runtime archive is empty.")
        } finally { connection.disconnect() }
    }

    private fun extract(zip: File, destination: File): Pair<Int, Long> {
        var entries = 0
        var expandedBytes = 0L
        val seen = HashSet<String>()
        val canonicalRoot = destination.canonicalFile.toPath()
        val expandedLimit = maxExpandedBytes
        ZipFile(zip).use { archive ->
            val allEntries = archive.entries()
            val files = mutableListOf<java.util.zip.ZipEntry>()
            var declaredExpandedBytes = 0L
            while (allEntries.hasMoreElements()) {
                val entry = allEntries.nextElement()
                entries++
                if (entries > maxEntries) throw IllegalStateException("Runtime archive contains too many entries.")
                if (!isSafeRuntimeArchiveEntry(entry.name, entry.isDirectory)) {
                    throw IllegalStateException("Unsafe runtime archive path rejected: ${entry.name.take(120)}")
                }
                val normalized = normalizeRuntimeArchiveEntry(entry.name, entry.isDirectory)
                    ?: throw IllegalStateException("Unsafe runtime archive path rejected: ${entry.name.take(120)}")
                if (normalized.isEmpty()) continue // harmless archive root directory entry "./"
                if (!seen.add(normalized)) throw IllegalStateException("Runtime archive contains duplicate path: $normalized")
                if (!entry.isDirectory) {
                    if (entry.size < 0L) throw IllegalStateException("Runtime archive entry has unknown expanded size: $normalized")
                    declaredExpandedBytes += entry.size
                    if (declaredExpandedBytes > expandedLimit) throw IllegalStateException("Expanded runtime exceeds configured size limit.")
                    files += entry
                }
            }
            if (entries == 0 || files.none { normalizeRuntimeArchiveEntry(it.name, false) == "runtime-manifest.json" }) {
                throw IllegalStateException("Runtime ZIP must contain runtime-manifest.json at its root.")
            }
            val available = StatFs(destination.absolutePath).availableBytes
            val required = declaredExpandedBytes + storageReserveBytes
            if (available < required) {
                throw IllegalStateException(
                    "Not enough free storage to extract runtime. Need " +
                        "${required / (1024 * 1024)} MiB including safety reserve; " +
                        "available ${available / (1024 * 1024)} MiB."
                )
            }
            val allForExtraction = archive.entries()
            while (allForExtraction.hasMoreElements()) {
                val entry = allForExtraction.nextElement()
                val normalized = normalizeRuntimeArchiveEntry(entry.name, entry.isDirectory)
                    ?: throw IllegalStateException("Unsafe runtime archive path rejected during extraction: ${entry.name.take(120)}")
                if (normalized.isEmpty()) continue
                val output = File(destination, normalized).canonicalFile
                if (!output.toPath().startsWith(canonicalRoot)) {
                    throw IllegalStateException("Runtime archive path escapes staging directory.")
                }
                if (entry.isDirectory) {
                    if (!output.mkdirs() && !output.isDirectory) throw IllegalStateException("Cannot create runtime directory: $normalized")
                } else {
                    if (!output.parentFile.mkdirs() && !output.parentFile.isDirectory) {
                        throw IllegalStateException("Cannot create runtime parent directory: $normalized")
                    }
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                expandedBytes += count
                                if (expandedBytes > expandedLimit) throw IllegalStateException("Expanded runtime exceeded configured size limit.")
                                stream.write(buffer, 0, count)
                            }
                        }
                    }
                    if (output.length() != entry.size) {
                        throw IllegalStateException("Runtime archive entry size mismatch: $normalized")
                    }
                }
            }
        }
        return entries to expandedBytes
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureAllowedGitHubResponse(connection: HttpURLConnection) {
        val finalUrl = connection.url
        require(finalUrl.protocol.equals("https", ignoreCase = true) &&
            finalUrl.host.lowercase(Locale.ROOT) in ALLOWED_GITHUB_HOSTS) {
            "GitHub redirected the runtime request to an unapproved host."
        }
    }

    private fun open(url: String): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true) &&
            parsed.host.lowercase(Locale.ROOT) in ALLOWED_GITHUB_HOSTS) {
            "Runtime downloads must use an approved HTTPS GitHub host."
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "MovingOut2Android-RuntimeManager")
        return connection
    }

    companion object {
        private val ALLOWED_GITHUB_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )
    }
}
