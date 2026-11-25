package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class SafGameImportProgress(val copiedBytes: Long, val totalBytes: Long, val copiedFiles: Int) {
    val percent: Int get() = if (totalBytes <= 0L) 0 else ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

data class SafGameImportResult(val gameRoot: File, val copiedBytes: Long, val copiedFiles: Int)

/** Copies a SAF tree into private storage with staging and rollback-safe activation. */
class SafGameImporter(private val context: Context) {
    fun import(
        uri: Uri,
        onProgress: (SafGameImportProgress) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): SafGameImportResult {
        val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!persisted) throw IOException("Persisted SAF read permission is missing or revoked.")
        val source = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("Selected SAF game directory cannot be opened.")
        if (!source.isDirectory || !source.canRead()) throw IOException("Selected SAF game directory is not readable.")

        val totalBytes = measureBytes(source)
        if (totalBytes <= 0L) throw IOException("SAF directory contains no readable game files.")
        val reserveBytes = 128L * 1024L * 1024L
        val requiredSpace = if (Long.MAX_VALUE - totalBytes < reserveBytes) Long.MAX_VALUE else totalBytes + reserveBytes
        if (context.filesDir.usableSpace < requiredSpace) {
            throw IOException("Insufficient app storage. Need at least ${requiredSpace / (1024 * 1024)} MiB free.")
        }

        val target = File(context.filesDir, "game_data")
        val staging = File(context.filesDir, "game_data.importing")
        val backup = File(context.filesDir, "game_data.previous")
        staging.deleteRecursively()
        backup.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) throw IOException("Unable to create game import staging directory.")

        val counters = longArrayOf(0L, 0L)
        try {
            copyDirectory(source, staging, counters, totalBytes, onProgress, cancelled)
            if (cancelled()) throw IOException("Game import cancelled.")
            val validation = InstallationValidator().validate(staging)
            if (validation.status != InstallationStatus.VALID ||
                validation.missingFiles.isNotEmpty() || validation.accessErrors.isNotEmpty()) {
                throw IOException("Copied game failed validation: ${validation.status}; missing=${validation.missingFiles.joinToString()}; errors=${validation.accessErrors.joinToString()}")
            }

            if (target.exists() && !target.renameTo(backup)) throw IOException("Unable to preserve previous game installation.")
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Unable to activate imported game directory.")
            }
            backup.deleteRecursively()
            onProgress(SafGameImportProgress(counters[0], totalBytes, counters[1].toInt()))
            return SafGameImportResult(target, counters[0], counters[1].toInt())
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun measureBytes(directory: DocumentFile): Long {
        var total = 0L
        val pending = ArrayDeque<DocumentFile>()
        pending.add(directory)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current.isDirectory) current.listFiles().forEach(pending::addLast)
            else if (current.isFile) {
                val length = current.length()
                if (length > 0L) total = if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
            }
        }
        return total
    }

    private fun copyDirectory(
        source: DocumentFile,
        destination: File,
        counters: LongArray,
        totalBytes: Long,
        progress: (SafGameImportProgress) -> Unit,
        cancelled: () -> Boolean
    ) {
        source.listFiles().forEach { child ->
            if (cancelled()) throw IOException("Game import cancelled.")
            val name = child.name ?: throw IOException("SAF entry has no name.")
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                throw IOException("Unsafe SAF entry name: $name")
            }
            val output = File(destination, name).canonicalFile
            if (!output.toPath().startsWith(destination.canonicalFile.toPath())) {
                throw IOException("SAF entry escapes game import directory.")
            }
            if (child.isDirectory) {
                if (!output.mkdirs() && !output.isDirectory) throw IOException("Unable to create game directory: $name")
                copyDirectory(child, output, counters, totalBytes, progress, cancelled)
            } else if (child.isFile) {
                val input = context.contentResolver.openInputStream(child.uri)
                    ?: throw IOException("Unable to read SAF file: $name")
                input.use { stream ->
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled()) throw IOException("Game import cancelled.")
                            val count = stream.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            counters[0] += count
                            progress(SafGameImportProgress(counters[0], totalBytes, counters[1].toInt()))
                        }
                    }
                }
                counters[1]++
                progress(SafGameImportProgress(counters[0], totalBytes, counters[1].toInt()))
            }
        }
    }
}
