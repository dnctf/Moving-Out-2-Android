package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

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

    fun importZip(uri: Uri): GameZipImportResult {
        val parent = root.parentFile ?: throw IOException("Private storage unavailable.")
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Cannot create private app storage.")
        if (!root.exists() && backup.exists() && !backup.renameTo(root)) throw IOException("Cannot recover previous game import.")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot create staging directory.")
        var entries = 0
        var bytes = 0L
        val seen = HashSet<String>()
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Unable to open selected ZIP.")
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        if (entries > maxEntries) throw IOException("ZIP has too many entries.")
                        val rawName = entry.name.replace('\\\\', '/')
                        val name = rawName.trimEnd('/')
                        if (name.isBlank() || name.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(name) ||
                            name.split('/').any { it == ".." || it.isBlank() || it == "." }) throw IOException("Unsafe ZIP path: $rawName")
                        if (!seen.add(name.lowercase(Locale.ROOT))) throw IOException("Duplicate ZIP entry: $rawName")
                        val out = File(staging, name).canonicalFile
                        if (!out.toPath().startsWith(staging.canonicalFile.toPath())) throw IOException("ZIP path escapes staging storage.")
                        if (entry.isDirectory) {
                            if (!out.mkdirs() && !out.isDirectory) throw IOException("Cannot create directory.")
                        } else {
                            if (!out.parentFile.mkdirs() && !out.parentFile.isDirectory) throw IOException("Cannot create extraction path.")
                            FileOutputStream(out).use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    bytes = Math.addExact(bytes, n.toLong())
                                    if (bytes > maxExpandedBytes) throw IOException("ZIP expansion exceeds configured limit.")
                                    sink.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val stat = StatFs(parent.absolutePath)
            if (stat.availableBytes - bytes < storageReserveBytes) throw IOException("Insufficient storage: keep at least 1.5 GiB free after import.")
            val candidate = findGameRoot(staging) ?: throw IOException("ZIP does not contain a recognizable game root.")
            val report = InstallationValidator().validate(candidate)
            if (report.status != InstallationStatus.VALID) {
                throw IOException("Staging validation failed: missing=${report.missingFiles.joinToString()}, errors=${report.accessErrors.joinToString()}")
            }
            if (root.exists()) {
                backup.deleteRecursively()
                if (!root.renameTo(backup)) throw IOException("Unable to preserve previous imported game.")
            }
            if (!staging.renameTo(root)) throw IOException("Unable to activate extracted game.")
            val active = findGameRoot(root) ?: throw IOException("Activated game root could not be resolved.")
            backup.deleteRecursively()
            return GameZipImportResult(entries, bytes, active)
        } catch (e: Exception) {
            staging.deleteRecursively()
            if (backup.exists()) {
                if (root.exists()) root.deleteRecursively()
                if (!backup.renameTo(root)) throw IOException("Import failed and automatic rollback could not restore previous installation.", e)
            }
            throw e
        }
    }

    private fun findGameRoot(base: File): File? {
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(base to 0)
        while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeFirst()
            if (File(dir, "mo2.exe").isFile && File(dir, "MovingOut2_Data").isDirectory) return dir
            if (depth >= 4) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { pending.add(it to depth + 1) }
        }
        return null
    }
}
