package com.dnctf.movingout2.runtime

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bounded, path-safe archive transfer for the Wine user's LocalLow save-data tree. */
object WineSaveDataManager {
    private const val MAX_ENTRIES = 20_000
    private const val MAX_EXPANDED_BYTES = 2L * 1024L * 1024L * 1024L

    fun export(localLow: File, destinationZip: File): Long {
        if (!localLow.isDirectory || !localLow.canRead()) throw IOException("Wine LocalLow save directory is unavailable.")
        destinationZip.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IOException("Cannot create save export directory.") }
        var written = 0L
        ZipOutputStream(FileOutputStream(destinationZip)).use { zip ->
            localLow.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(localLow).invariantSeparatorsPath
                validateRelativePath(relative)
                zip.putNextEntry(ZipEntry(relative))
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written = Math.addExact(written, count.toLong())
                        if (written > MAX_EXPANDED_BYTES) throw IOException("Save data exceeds export size limit.")
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
            }
        }
        return written
    }

    fun import(sourceZip: File, localLow: File): Long {
        if (!sourceZip.isFile || !sourceZip.canRead()) throw IOException("Save archive is unavailable.")
        val root = localLow.canonicalFile
        if (!root.mkdirs() && !root.isDirectory) throw IOException("Cannot create Wine LocalLow save directory.")
        var entries = 0
        var written = 0L
        ZipInputStream(FileInputStream(sourceZip)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw IOException("Save archive contains too many entries.")
                validateRelativePath(entry.name)
                val output = File(root, entry.name).canonicalFile
                if (!output.toPath().startsWith(root.toPath())) throw IOException("Save archive entry escapes LocalLow.")
                if (entry.isDirectory) {
                    if (!output.mkdirs() && !output.isDirectory) throw IOException("Cannot create save directory.")
                } else {
                    output.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IOException("Cannot create save parent directory.") }
                    FileOutputStream(output).use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written = Math.addExact(written, count.toLong())
                            if (written > MAX_EXPANDED_BYTES) throw IOException("Save archive exceeds expanded size limit.")
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return written
    }

    private fun validateRelativePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') ||
            path.split('/', '\\').any { it.isBlank() || it == "." || it == ".." } ||
            path.contains(':')) throw IOException("Unsafe save archive path: $path")
    }
}
