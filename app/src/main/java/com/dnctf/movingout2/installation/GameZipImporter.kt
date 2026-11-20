package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

data class GameZipImportResult(val fileCount: Int, val bytesWritten: Long, val gameRoot: File)

class GameZipImporter(
    context: Context,
    private val maxExpandedBytes: Long = 16L * 1024L * 1024L * 1024L,
    private val maxEntries: Int = 100_000
) {
    private val resolver = context.contentResolver
    private val root = File(context.filesDir, "game_import")
    private val staging = File(context.filesDir, "game_import_staging")
    private val backup = File(context.filesDir, "game_import_backup")

    fun importZip(uri: Uri): GameZipImportResult {
        if (!root.parentFile!!.mkdirs() && !root.parentFile!!.isDirectory) throw IOException("Cannot create private app storage.")
        if (!root.exists() && backup.exists() && !backup.renameTo(root)) throw IOException("Cannot recover previous game import.")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot create staging directory.")
        var entries = 0
        var bytes = 0L
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Unable to open selected ZIP.")
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        if (entries > maxEntries) throw IOException("ZIP has too many entries.")
                        val rawName = entry.name.replace('\\', '/')
                        val name = rawName.trimEnd('/')
                        if (name.isBlank() || name.startsWith('/') || name.split('/').any { it == ".." || it.isBlank() }) throw IOException("Unsafe ZIP path: $rawName")
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
                                    bytes += n
                                    if (bytes > maxExpandedBytes) throw IOException("ZIP expansion exceeds 16 GiB limit.")
                                    sink.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val candidates = mutableListOf<File>()
            fun scan(dir: File, depth: Int) {
                if (depth > 4) return
                candidates += dir
                dir.listFiles()?.filter { it.isDirectory }?.forEach { scan(it, depth + 1) }
            }
            scan(staging, 0)
            val found = candidates.any { dir -> File(dir, "mo2.exe").isFile && File(dir, "MovingOut2_Data").isDirectory }
            if (!found) throw IOException("ZIP does not contain mo2.exe and MovingOut2_Data at a recognizable game root.")
            if (root.exists()) {
                backup.deleteRecursively()
                if (!root.renameTo(backup)) throw IOException("Unable to preserve previous imported game.")
            }
            if (!staging.renameTo(root)) throw IOException("Unable to activate extracted game.")
            backup.deleteRecursively()
            val activeCandidates = mutableListOf<File>()
            fun findRoots(dir: File, depth: Int) {
                if (depth > 4) return
                activeCandidates += dir
                dir.listFiles()?.filter { it.isDirectory }?.forEach { findRoots(it, depth + 1) }
            }
            findRoots(root, 0)
            val activeRoot = activeCandidates.first { File(it, "mo2.exe").isFile && File(it, "MovingOut2_Data").isDirectory }
            return GameZipImportResult(entries, bytes, activeRoot)
        } catch (e: Exception) {
            staging.deleteRecursively()
            if (backup.exists()) {
                root.deleteRecursively()
                backup.renameTo(root)
            }
            throw e
        }
    }
}
