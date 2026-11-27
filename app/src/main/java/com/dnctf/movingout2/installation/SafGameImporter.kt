package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SafGameImportProgress(val copiedBytes: Long, val totalBytes: Long, val copiedFiles: Int) {
    val percent: Int get() = if (totalBytes <= 0L) 0 else ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

data class SafGameImportResult(val gameRoot: File, val copiedBytes: Long, val copiedFiles: Int)

/** Copies a SAF tree into private storage with bounded traversal and rollback-safe activation. */
class SafGameImporter(
    private val context: Context,
    private val maxExpandedBytes: Long = 16L * 1024L * 1024L * 1024L,
    private val maxEntries: Int = 100_000,
    private val maxDepth: Int = 24,
    private val storageReserveBytes: Long = 128L * 1024L * 1024L
) {
    companion object {
        private val importLock = ReentrantLock()
    }

    private val stageDiagnostics = RuntimeStageDiagnostics(context)

    fun import(
        uri: Uri,
        onProgress: (SafGameImportProgress) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): SafGameImportResult = importLock.withLock {
        stageDiagnostics.event("STAGE_1_SAF_IMPORT", "START", mapOf("uriScheme" to uri.scheme))
        runCatching { importLocked(uri, onProgress, cancelled) }
            .onSuccess { stageDiagnostics.event("STAGE_1_SAF_IMPORT", "PASS", mapOf("files" to it.copiedFiles, "bytes" to it.copiedBytes, "integrity" to "validated")) }
            .onFailure { stageDiagnostics.event("STAGE_1_SAF_IMPORT", "FAIL", error = it) }
            .getOrThrow()
    }

    private fun importLocked(
        uri: Uri,
        onProgress: (SafGameImportProgress) -> Unit,
        cancelled: () -> Boolean
    ): SafGameImportResult {
        val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!persisted) throw IOException("Persisted SAF read permission is missing or revoked.")
        val source = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("Selected SAF game directory cannot be opened.")
        if (!source.isDirectory || !source.canRead()) throw IOException("Selected SAF game directory is not readable.")
        if (maxExpandedBytes <= 0L || maxEntries <= 0 || maxDepth < 0 || storageReserveBytes < 0L) {
            throw IOException("SAF import safety limits are invalid.")
        }

        val totalBytes = measureBytes(source)
        if (totalBytes <= 0L) throw IOException("SAF directory contains no readable game files.")
        val requiredSpace = try {
            safGameImportRequiredSpace(totalBytes, storageReserveBytes)
        } catch (error: ArithmeticException) {
            throw IOException("SAF import storage requirement overflowed.", error)
        }
        val availableBefore = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
            .getOrElse { throw IOException("Unable to determine available app storage.", it) }
        if (!safGameImportStorageSatisfied(availableBefore, requiredSpace)) {
            throw IOException("Insufficient app storage. Extraction and reserve require at least ${requiredSpace / (1024 * 1024)} MiB free.")
        }

        val target = File(context.filesDir, "game_data")
        val staging = File(context.filesDir, "game_data.importing")
        val backup = File(context.filesDir, "game_data.previous")
        recoverSafGameImport(target, backup) { candidate ->
            val report = InstallationValidator().validate(candidate)
            report.status == InstallationStatus.VALID &&
                report.missingFiles.isEmpty() && report.accessErrors.isEmpty()
        }
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Unable to clean interrupted SAF staging directory.")
        }
        if (!staging.mkdirs()) throw IOException("Unable to create game import staging directory.")

        // [0] bytes copied, [1] files copied, [2] entries copied, [3] bytes at last free-space check.
        val counters = longArrayOf(0L, 0L, 0L, 0L)
        val visitedDirectories = mutableSetOf(source.uri.toString())
        try {
            copyDirectory(source, staging, 0, counters, totalBytes, onProgress, cancelled, visitedDirectories)
            if (cancelled()) throw IOException("Game import cancelled.")
            val availableAfterCopy = StatFs(context.filesDir.absolutePath).availableBytes
            if (!safGameImportStorageSatisfied(availableAfterCopy, storageReserveBytes)) {
                throw IOException("SAF game import aborted: free storage fell below the configured reserve.")
            }

            val validation = InstallationValidator().validate(staging)
            if (validation.status != InstallationStatus.VALID ||
                validation.missingFiles.isNotEmpty() || validation.accessErrors.isNotEmpty()) {
                throw IOException("Copied game failed validation: ${validation.status}; missing=${validation.missingFiles.joinToString()}; errors=${validation.accessErrors.joinToString()}")
            }

            if (target.exists()) {
                if (backup.exists()) throw IOException("Previous game backup still exists after recovery.")
                if (!target.renameTo(backup)) throw IOException("Unable to preserve previous game installation.")
            }
            if (!staging.renameTo(target)) throw IOException("Unable to activate imported game directory.")

            val activeValidation = InstallationValidator().validate(target)
            if (activeValidation.status != InstallationStatus.VALID ||
                activeValidation.missingFiles.isNotEmpty() || activeValidation.accessErrors.isNotEmpty()) {
                throw IOException("Activated game failed revalidation; previous installation will be restored.")
            }

            // A valid active target is authoritative. A stale backup can be cleaned on the next recovery pass.
            if (backup.exists()) backup.deleteRecursively()
            onProgress(SafGameImportProgress(counters[0], totalBytes, counters[1].toInt()))
            return SafGameImportResult(target, counters[0], counters[1].toInt())
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (backup.exists()) {
                if (target.exists() && !target.deleteRecursively()) {
                    throw IOException("SAF import failed and the new target could not be removed; previous game remains in backup.", error)
                }
                if (!backup.renameTo(target)) {
                    throw IOException("SAF import failed and rollback could not restore the previous game; backup remains at ${backup.absolutePath}.", error)
                }
            }
            throw error
        }
    }

    private fun measureBytes(directory: DocumentFile): Long {
        var total = 0L
        var entries = 0
        val pending = ArrayDeque<Pair<DocumentFile, Int>>()
        val visitedDirectories = mutableSetOf(directory.uri.toString())
        pending.add(directory to 0)
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeFirst()
            if (depth > maxDepth) throw IOException("SAF game directory depth exceeds $maxDepth.")
            val names = SafGameEntryNameRegistry()
            for (child in current.listFiles()) {
                entries = try { Math.addExact(entries, 1) } catch (error: ArithmeticException) {
                    throw IOException("SAF entry count overflowed.", error)
                }
                if (entries > maxEntries) throw IOException("SAF game directory exceeds $maxEntries entries.")
                names.register(child.name ?: throw IOException("SAF entry has no name."))
                when {
                    child.isDirectory -> {
                        if (depth >= maxDepth) throw IOException("SAF game directory depth exceeds $maxDepth.")
                        if (!visitedDirectories.add(child.uri.toString())) {
                            throw IOException("SAF tree contains a repeated or cyclic directory reference.")
                        }
                        pending.add(child to depth + 1)
                    }
                    child.isFile -> {
                        val length = child.length()
                        if (length < 0L) throw IOException("SAF file reports an invalid size.")
                        total = try { Math.addExact(total, length) } catch (error: ArithmeticException) {
                            throw IOException("SAF expanded size overflowed.", error)
                        }
                        if (total > maxExpandedBytes) throw IOException("SAF game directory exceeds the $maxExpandedBytes byte expansion limit.")
                    }
                    else -> throw IOException("Unsupported SAF entry: ${child.name ?: "(unnamed)"}")
                }
            }
        }
        return total
    }

    private fun copyDirectory(
        source: DocumentFile,
        destination: File,
        depth: Int,
        counters: LongArray,
        totalBytes: Long,
        progress: (SafGameImportProgress) -> Unit,
        cancelled: () -> Boolean,
        visitedDirectories: MutableSet<String>
    ) {
        if (depth > maxDepth) throw IOException("SAF game directory depth exceeds $maxDepth.")
        val names = SafGameEntryNameRegistry()
        for (child in source.listFiles()) {
            if (cancelled()) throw IOException("Game import cancelled.")
            val name = names.register(child.name ?: throw IOException("SAF entry has no name."))
            counters[2] = try { Math.addExact(counters[2], 1L) } catch (error: ArithmeticException) {
                throw IOException("SAF entry count overflowed during copy.", error)
            }
            if (counters[2] > maxEntries.toLong()) throw IOException("SAF game directory changed and now exceeds $maxEntries entries.")

            val output = File(destination, name).canonicalFile
            if (!output.toPath().startsWith(destination.canonicalFile.toPath())) {
                throw IOException("SAF entry escapes game import directory.")
            }
            if (child.isDirectory) {
                if (depth >= maxDepth) throw IOException("SAF game directory depth exceeds $maxDepth.")
                if (!visitedDirectories.add(child.uri.toString())) {
                    throw IOException("SAF tree changed and contains a repeated or cyclic directory reference.")
                }
                if (!output.mkdirs() && !output.isDirectory) throw IOException("Unable to create game directory: $name")
                copyDirectory(child, output, depth + 1, counters, totalBytes, progress, cancelled, visitedDirectories)
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
                            val nextBytes = try { Math.addExact(counters[0], count.toLong()) } catch (error: ArithmeticException) {
                                throw IOException("SAF copied byte count overflowed.", error)
                            }
                            if (nextBytes > maxExpandedBytes) throw IOException("SAF game copy exceeded the $maxExpandedBytes byte expansion limit.")
                            if (nextBytes - counters[3] >= 4L * 1024L * 1024L) {
                                val available = StatFs(context.filesDir.absolutePath).availableBytes
                                if (!safGameImportStorageSatisfied(available, storageReserveBytes)) {
                                    throw IOException("SAF game copy stopped: free storage fell below the configured reserve.")
                                }
                                counters[3] = nextBytes
                            }
                            out.write(buffer, 0, count)
                            counters[0] = nextBytes
                            progress(SafGameImportProgress(counters[0], totalBytes, counters[1].toInt()))
                        }
                    }
                }
                counters[1] = try { Math.addExact(counters[1], 1L) } catch (error: ArithmeticException) {
                    throw IOException("SAF copied file count overflowed.", error)
                }
                progress(SafGameImportProgress(counters[0], totalBytes, counters[1].toInt()))
            } else {
                throw IOException("Unsupported SAF entry: $name")
            }
        }
    }
}

/** Rejects traversal names and sibling collisions after Unicode compatibility/case normalization. */
internal class SafGameEntryNameRegistry {
    private val names = mutableSetOf<String>()

    fun register(name: String): String {
        if (name.isBlank() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw IOException("Unsafe SAF entry name: ${name.take(160)}")
        }
        val key = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (!names.add(key)) throw IOException("Duplicate/colliding SAF entry name: ${name.take(160)}")
        return name
    }
}

/** Restore or preserve a previous game tree before a new SAF import is allowed to clean staging. */
internal fun recoverSafGameImport(target: File, backup: File, isValid: (File) -> Boolean) {
    if (!backup.exists()) return
    val targetValid = target.exists() && runCatching { isValid(target) }.getOrDefault(false)
    val backupValid = runCatching { isValid(backup) }.getOrDefault(false)
    when {
        targetValid -> {
            if (!backup.deleteRecursively()) throw IOException("Unable to remove stale SAF game backup.")
        }
        backupValid -> {
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Unable to remove invalid active game tree while restoring backup.")
            }
            if (!backup.renameTo(target)) throw IOException("Unable to restore previous SAF game installation.")
        }
        else -> throw IOException("SAF game recovery found no valid active or backup installation; both were preserved.")
    }
}

internal fun safGameImportRequiredSpace(expandedBytes: Long, reserveBytes: Long): Long {
    if (expandedBytes < 0L || reserveBytes < 0L) throw IllegalArgumentException("SAF storage requirements cannot be negative.")
    return Math.addExact(expandedBytes, reserveBytes)
}

internal fun safGameImportStorageSatisfied(availableBytes: Long, requiredBytes: Long): Boolean =
    requiredBytes >= 0L && availableBytes >= requiredBytes
