package com.dnctf.movingout2.runtime

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class RuntimeRepositoryInstallResult(val version: String, val files: Int, val bytes: Long)

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
    private val maxExpandedBytes: Long = 6L * 1024L * 1024L * 1024L
) {
    private val appContext = context.applicationContext
    private val parent = File(appContext.filesDir, "native_runtime")
    private val target = File(parent, "bundle")
    private val staging = File(parent, "bundle_staging")
    private val backup = File(parent, "bundle_backup")
    private val archive = File(appContext.cacheDir, "runtime-download.zip")

    fun installLatest(onProgress: (String) -> Unit = {}): RuntimeRepositoryInstallResult {
        onProgress("Checking latest runtime release…")
        val releaseUrl = "https://api.github.com/repos/$repository/releases/latest"
        val release = JSONObject(readUrl(releaseUrl, 2 * 1024 * 1024))
        val version = release.optString("tag_name").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub release has no tag_name.")
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
        val bundleUrl = zipUrl ?: throw IllegalStateException(
            "Release $version does not publish $zipName. Runtime bundle has not been published yet."
        )
        val digestUrl = checksumUrl ?: throw IllegalStateException(
            "Release $version is missing $checksumName; refusing an unverified runtime download."
        )

        onProgress("Downloading and verifying SHA-256…")
        val expectedHash = readUrl(digestUrl, 4096).trim().substringBefore(Regex("\\s")).lowercase()
        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Release checksum file is malformed." }
        download(bundleUrl, archive, onProgress)
        val actualHash = sha256(archive)
        if (actualHash != expectedHash) {
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
            val report = validator.validate(staging)
            if (!report.isReady) throw IllegalStateException(
                "Downloaded runtime failed manifest/ABI validation: " + report.blockers.joinToString("; ")
            )
            listOf("box64/bin/box64", "wine/bin/wine64", "wine/bin/wineserver").forEach { relative ->
                val file = File(staging, relative)
                if (!file.setExecutable(true, false) && !file.canExecute()) {
                    throw IllegalStateException("Unable to mark runtime executable: $relative")
                }
            }
            val finalCheck = validator.validate(staging)
            if (!finalCheck.isReady) throw IllegalStateException("Runtime failed post-extraction validation.")
            if (target.exists() && !target.renameTo(backup)) throw IllegalStateException("Cannot preserve current runtime bundle.")
            if (!staging.renameTo(target)) throw IllegalStateException("Cannot activate downloaded runtime bundle.")
            activated = true
            val active = validator.validate(target)
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

    private fun readUrl(url: String, limit: Int): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Repository request failed: HTTP ${connection.responseCode}")
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
            if (connection.responseCode !in 200..299) throw IllegalStateException("Runtime download failed: HTTP ${connection.responseCode}")
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
        var bytes = 0L
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries++
                if (entries > maxEntries) throw IllegalStateException("Runtime archive contains too many entries.")
                val name = entry.name
                if (name.isBlank() || name.startsWith("/") || name.startsWith("\\\\") ||
                    name.split('/', '\\\\').any { it == ".." } || name.contains(':') || name.any { it.code < 32 }) {
                    throw IllegalStateException("Unsafe runtime archive path rejected.")
                }
                val output = File(destination, name).canonicalFile
                if (!output.toPath().startsWith(destination.canonicalFile.toPath())) {
                    throw IllegalStateException("Runtime archive path escapes staging directory.")
                }
                if (entry.isDirectory) {
                    if (!output.mkdirs() && !output.isDirectory) throw IllegalStateException("Cannot create runtime directory.")
                } else {
                    if (!output.parentFile.mkdirs() && !output.parentFile.isDirectory) throw IllegalStateException("Cannot create runtime parent directory.")
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > maxExpandedBytes) throw IllegalStateException("Expanded runtime exceeds configured size limit.")
                            stream.write(buffer, 0, count)
                        }
                    }
                }
                input.closeEntry()
            }
        }
        if (entries == 0 || !File(destination, "runtime-manifest.json").isFile) {
            throw IllegalStateException("Runtime ZIP must contain runtime-manifest.json at its root.")
        }
        return entries to bytes
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "MovingOut2Android-RuntimeManager")
        return connection
    }
}
