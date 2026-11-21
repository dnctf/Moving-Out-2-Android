package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

data class ImportProgress(val phase: String, val entriesDone: Int, val totalEntries: Int, val bytesDone: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes > 0) ((bytesDone * 100L) / totalBytes).toInt().coerceIn(0, 100) else if (totalEntries > 0) ((entriesDone * 100) / totalEntries).coerceIn(0, 100) else 0
}
data class GameZipImportResult(val fileCount: Int, val bytesWritten: Long, val gameRoot: File)

class GameZipImporter(
    context: Context,
    private val maxExpandedBytes: Long = 16L * 1024L * 1024L * 1024L,
    private val maxEntries: Int = 100_000,
    private val storageReserveBytes: Long = 1_610_612_736L
) {
    private val resolver = context.contentResolver
    private val root = File(context.filesDir, "game_import")
    private val staging = File(context.filesDir, "game_import_staging")
    private val backup = File(context.filesDir, "game_import_backup")
    private val sourceZip = File(context.cacheDir, "game_import_preflight.zip")

    fun importZip(uri: Uri, cancelled: () -> Boolean = { false }, onProgress: (ImportProgress) -> Unit = {}): GameZipImportResult {
        val parent = root.parentFile ?: throw IOException("Private storage unavailable.")
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create private app storage.")
        recoverPreviousImport()
        try {
            copyUriToPreflight(uri, cancelled)
            ZipFile(sourceZip).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.isEmpty()) throw IOException("ZIP is empty.")
                if (entries.size > maxEntries) throw IOException("ZIP has too many entries.")
                val names = ZipEntryPathRegistry()
                var totalBytes = 0L
                val normalized = entries.map { entry ->
                    checkCancellation(cancelled)
                    val name = names.register(entry.name)
                    if (!entry.isDirectory) {
                        if (entry.size < 0) throw IOException("ZIP entry has unknown size: $name")
                        totalBytes = Math.addExact(totalBytes, entry.size)
                        if (totalBytes > maxExpandedBytes) throw IOException("ZIP expansion exceeds configured limit.")
                    }
                    entry to name
                }
                val candidateRootPrefix = GameZipRootLocator.findRootPrefix(normalized.map { it.second })
                    ?: throw IOException("ZIP central directory does not contain the required Unity game files.")
                val stat = StatFs(parent.absolutePath)
                val archiveBytes = sourceZip.length()
                val requiredSpace = Math.addExact(Math.addExact(totalBytes, archiveBytes), storageReserveBytes)
                if (stat.availableBytes < requiredSpace) throw IOException("Insufficient storage. ZIP copy, extraction ($totalBytes bytes), and 1.5 GiB reserve require approximately $requiredSpace bytes.")
                staging.deleteRecursively()
                if (!staging.mkdirs()) throw IOException("Cannot create staging directory.")
                var bytesWritten = 0L
                var entriesDone = 0
                onProgress(ImportProgress("Extracting", 0, normalized.size, 0, totalBytes))
                for ((entry, name) in normalized) {
                    checkCancellation(cancelled)
                    val out = File(staging, name).canonicalFile
                    if (!out.toPath().startsWith(staging.canonicalFile.toPath())) throw IOException("ZIP path escapes staging storage.")
                    if (entry.isDirectory) {
                        if (!out.mkdirs() && !out.isDirectory) throw IOException("Cannot create directory.")
                    } else {
                        val parentDir = out.parentFile ?: throw IOException("Invalid extraction path.")
                        if (!parentDir.mkdirs() && !parentDir.isDirectory) throw IOException("Cannot create extraction path.")
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(out).use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkCancellation(cancelled)
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    bytesWritten = Math.addExact(bytesWritten, n.toLong())
                                    if (bytesWritten > maxExpandedBytes || bytesWritten > totalBytes) throw IOException("ZIP expansion exceeded preflight size.")
                                    sink.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                    entriesDone++
                    onProgress(ImportProgress("Extracting", entriesDone, normalized.size, bytesWritten, totalBytes))
                }
                val candidate = File(staging, candidateRootPrefix).canonicalFile
                val validation = InstallationValidator().validate(candidate)
                if (validation.status != InstallationStatus.VALID) throw IOException("Staging validation failed: missing=${validation.missingFiles.joinToString()}, errors=${validation.accessErrors.joinToString()}")
                activateWithRollback(candidateRootPrefix)
                val active = findGameRoot(root) ?: throw IOException("Activated game root could not be resolved.")
                backup.deleteRecursively()
                onProgress(ImportProgress("Complete", normalized.size, normalized.size, bytesWritten, totalBytes))
                return GameZipImportResult(entriesDone, bytesWritten, active)
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            rollbackActivation()
            throw e
        } finally {
            sourceZip.delete()
        }
    }

    private fun copyUriToPreflight(uri: Uri, cancelled: () -> Boolean) {
        sourceZip.delete()
        val advertisedSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
        }
        val stat = StatFs(root.parentFile?.absolutePath ?: throw IOException("Private storage unavailable."))
        if (advertisedSize != null && stat.availableBytes < advertisedSize + storageReserveBytes) {
            throw IOException("Not enough free space to stage the ZIP safely.")
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("Unable to open selected ZIP.")
        input.use { raw ->
            FileOutputStream(sourceZip).use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                while (true) {
                    checkCancellation(cancelled)
                    val n = raw.read(buffer)
                    if (n < 0) break
                    copied = Math.addExact(copied, n.toLong())
                    if (copied > maxExpandedBytes || (advertisedSize != null && copied > advertisedSize)) {
                        throw IOException("ZIP source exceeds safe staging limit or advertised size.")
                    }
                    output.write(buffer, 0, n)
                }
                if (advertisedSize != null && copied != advertisedSize) throw IOException("ZIP source size changed or was truncated during staging.")
            }
        }
    }

    private fun activateWithRollback(candidatePrefix: String) {
        if (root.exists()) {
            backup.deleteRecursively()
            if (!root.renameTo(backup)) throw IOException("Unable to preserve previous imported game.")
        }
        if (!staging.renameTo(root)) {
            rollbackActivation()
            throw IOException("Unable to activate extracted game.")
        }
        val activeCandidate = File(root, candidatePrefix).canonicalFile
        val report = InstallationValidator().validate(activeCandidate)
        if (report.status != InstallationStatus.VALID) {
            rollbackActivation()
            throw IOException("Post-activation validation failed; previous installation restored.")
        }
    }

    private fun recoverPreviousImport() {
        if (backup.exists()) {
            val activeRoot = findGameRoot(root)
            val activeValid = activeRoot != null && runCatching { InstallationValidator().validate(activeRoot).status == InstallationStatus.VALID }.getOrDefault(false)
            if (!activeValid) {
                root.deleteRecursively()
                if (!backup.renameTo(root)) throw IOException("Cannot recover previous game import.")
            } else {
                backup.deleteRecursively()
            }
        }
    }

    private fun rollbackActivation() {
        if (backup.exists()) {
            if (root.exists()) root.deleteRecursively()
            if (!backup.renameTo(root)) throw IOException("Import failed and automatic rollback could not restore previous installation.")
        }
    }

    private fun findGameRoot(base: File): File? {
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(base to 0)
        while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeFirst()
            if (File(dir, "mo2.exe").isFile && File(dir, "MovingOut2_Data").isDirectory) return dir
            if (depth >= 12) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { pending.add(it to depth + 1) }
        }
        return null
    }

    private fun checkCancellation(cancelled: () -> Boolean) {
        if (cancelled()) throw ImportCancelledException()
    }

    class ImportCancelledException : IOException("Import cancelled by user.")
}

/** Pure ZIP central-directory root discovery; safe to test without Android Context. */
internal object GameZipRootLocator {
    fun findRootPrefix(names: List<String>): String? {
        val files = names.filterNot { it.endsWith('/') }.map { it.lowercase(Locale.ROOT) }.toSet()
        val exe = names.filter { it.equals("mo2.exe", true) || it.lowercase(Locale.ROOT).endsWith("/mo2.exe") }
        val required = listOf("gameassembly.dll", "unityplayer.dll", "baselib.dll", "movingout2_data/il2cpp_data/metadata/global-metadata.dat")
        for (exePath in exe) {
            val prefix = exePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            if (required.all { req -> files.contains((prefix + req).lowercase(Locale.ROOT)) }) return prefix
        }
        return null
    }
}
