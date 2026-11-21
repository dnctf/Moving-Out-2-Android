package com.dnctf.movingout2.runtime

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.os.StatFs
import com.dnctf.movingout2.core.logging.ImportDiagnosticJournal
import java.util.Properties

data class RuntimeImportResult(val report: RuntimeBundleReport, val copiedFiles: Int, val copiedBytes: Long)

class RuntimeBundleImporter(
    context: Context,
    private val validator: RuntimeBundleValidator = RuntimeBundleValidator(),
    private val maxBytes: Long = 6L * 1024L*1024L*1024L,
    private val maxEntries: Int = 20_000,
    private val maxDepth: Int = 24,
    private val storageReserveBytes: Long = 512L * 1024L * 1024L
) {
    private val resolver = context.contentResolver
    private val parent = File(context.filesDir, "native_runtime")
    private val target = File(parent, "bundle")
    private val staging = File(parent, "bundle_staging")
    private val backup = File(parent, "bundle_backup")

    fun importTree(uri: Uri): RuntimeImportResult {
        val journal = ImportDiagnosticJournal(File(contextHolder.filesDir, "diagnostics"))
        val archiveName = uri.lastPathSegment
        val availableBefore = runCatching { StatFs(parent.absolutePath).availableBytes }.getOrNull()
        val counter = Counter()
        journal.checkpoint("RUNTIME_BUNDLE", archiveName, stage="IMPORT_START", availableBytesBefore=availableBefore)
        val source = DocumentFile.fromTreeUri(resolverContext(), uri)
        if (source == null || !source.isDirectory || !source.canRead()) throw IOException("Selected SAF tree is not readable.")
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Unable to create private runtime storage.")
        if (storageReserveBytes < 0) throw IOException("Runtime storage reserve must not be negative.")
        if (availableBefore != null && !runtimeBundleHasReserve(availableBefore, storageReserveBytes)) {
            throw IOException("Insufficient storage: preserve at least ${storageReserveBytes} bytes before runtime import.")
        }
        // Validate both sides before cleanup: a crash can leave an invalid new target beside the old backup.
        recoverRuntimeBundle(target, backup) { bundle -> validator.validate(bundle).isReady }
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Unable to create runtime staging directory.")
        var activated = false
        try {
            copyDirectory(source, staging, 0, counter)
            val availableAfterCopy = StatFs(parent.absolutePath).availableBytes
            if (!runtimeBundleHasReserve(availableAfterCopy, storageReserveBytes)) {
                throw IOException("Runtime import aborted: free storage fell below configured reserve after extraction.")
            }
            journal.checkpoint("RUNTIME_BUNDLE", archiveName, entryCount=counter.entries, extractedBytes=counter.bytes, availableBytesBefore=availableBefore, availableBytesAfter=availableAfterCopy, stage="STAGING_VALIDATION")
            grantExecutableFlags(staging)
            val validation = validator.validate(staging)
            if (!validation.isReady) throw IOException("Runtime bundle failed validation: " + validation.blockers.joinToString("; "))
            if (target.exists() && !target.renameTo(backup)) throw IOException("Unable to preserve existing runtime bundle.")
            if (!staging.renameTo(target)) throw IOException("Unable to activate staged runtime bundle.")
            activated = true
            val active = validator.validate(target)
            if (!active.isReady) throw IOException("Installed bundle revalidation failed: " + active.blockers.joinToString("; "))
            backup.deleteRecursively()
            journal.checkpoint("RUNTIME_BUNDLE", archiveName, entryCount=counter.entries, extractedBytes=counter.bytes, availableBytesBefore=availableBefore, availableBytesAfter=runCatching { StatFs(parent.absolutePath).availableBytes }.getOrNull(), stage="ACTIVATED", activationStatus="SUCCESS", rollbackStatus="NOT_REQUIRED")
            return RuntimeImportResult(active, counter.files, counter.bytes)
        } catch (error: Exception) {
            val rollback = runCatching { rollbackImport(staging, backup, target, activated, error) }
            journal.checkpoint("RUNTIME_BUNDLE", archiveName, entryCount=counter.entries, extractedBytes=counter.bytes, availableBytesBefore=availableBefore, availableBytesAfter=runCatching { StatFs(parent.absolutePath).availableBytes }.getOrNull(), stage="IMPORT_FAILED", activationStatus="FAILED", rollbackStatus=if (rollback.isSuccess) "ROLLBACK_COMPLETED" else "ROLLBACK_FAILED", error=rollback.exceptionOrNull() ?: error)
            if (rollback.isFailure) throw IOException("Runtime import failed and rollback failed.", rollback.exceptionOrNull())
            throw error
        }
    }

    private fun resolverContext() = contextHolder

    private val contextHolder: Context = context

    private class Counter(var files: Int = 0, var entries: Int = 0, var bytes: Long = 0)

    private fun copyDirectory(source: DocumentFile, destination: File, depth: Int, counter: Counter) {
        if (depth > maxDepth) throw IOException("Runtime bundle directory depth exceeds limit.")
        for (child in source.listFiles()) {
            counter.entries++
            if (counter.entries > maxEntries) throw IOException("Runtime bundle contains too many entries.")
            val name = child.name ?: throw IOException("SAF entry has no name.")
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.any { it.code < 32 }) {
                throw IOException("Unsafe runtime bundle entry name.")
            }
            val output = File(destination, name)
            if (child.isDirectory) {
                if (!output.mkdirs() && !output.isDirectory) throw IOException("Unable to create runtime directory: $name")
                copyDirectory(child, output, depth + 1, counter)
            } else if (child.isFile) {
                counter.files++
                val input = resolver.openInputStream(child.uri) ?: throw IOException("Unable to open runtime file: $name")
                input.use { stream ->
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var lastSpaceCheckBytes = counter.bytes
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            counter.bytes += read.toLong()
                            if (counter.bytes > maxBytes) throw IOException("Runtime bundle exceeds configured import size limit.")
                            if (counter.bytes - lastSpaceCheckBytes >= 4L * 1024L * 1024L) {
                                val available = StatFs(parent.absolutePath).availableBytes
                                if (!runtimeBundleHasReserve(available, storageReserveBytes)) {
                                    throw IOException("Runtime import aborted: free storage fell below the configured reserve.")
                                }
                                lastSpaceCheckBytes = counter.bytes
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
            } else {
                throw IOException("Unsupported SAF entry: $name")
            }
        }
    }

    private fun grantExecutableFlags(root: File) {
        val manifest = File(root, "runtime-manifest.json")
        val paths = if (manifest.isFile) listOf("box64/bin/box64", "wine/bin/wine64", "wine/bin/wineserver") else {
            val properties = Properties()
            File(root, "runtime.properties").inputStream().use { properties.load(it) }
            listOf("box64", "wine64", "wineserver").map { properties.getProperty(it)?.trim().orEmpty() }.filter { it.isNotBlank() }
        }
        for (relative in paths) {
            if (relative.startsWith("/") || relative.split('/', '\\').any { it == ".." }) throw IOException("Executable path is unsafe.")
            val file = File(root, relative).canonicalFile
            if (!file.toPath().startsWith(root.canonicalFile.toPath())) throw IOException("Executable path escapes bundle.")
            RuntimeExecutablePermissions.ensureExecutable(file, relative)
        }
    }
}


/** Recover interrupted activation without deleting a valid backup before validating the active bundle. */
internal fun recoverRuntimeBundle(target: File, backup: File, isValid: (File) -> Boolean) {
    if (!backup.exists()) return
    val activeValid = target.exists() && runCatching { isValid(target) }.getOrDefault(false)
    val backupValid = runCatching { isValid(backup) }.getOrDefault(false)
    when {
        activeValid -> {
            if (!backup.deleteRecursively()) throw IOException("Unable to remove stale runtime bundle backup.")
        }
        backupValid -> {
            if (target.exists() && !target.deleteRecursively()) throw IOException("Unable to remove invalid active runtime bundle during recovery.")
            if (!backup.renameTo(target)) throw IOException("Unable to restore valid runtime bundle backup.")
        }
        else -> throw IOException("Runtime bundle recovery found no valid active or backup bundle; both were preserved.")
    }
}

/** Rollback is intentionally loud: never silently hide failure to preserve or restore the previous bundle. */
internal fun rollbackImport(staging: File, backup: File, target: File, activated: Boolean, cause: Exception) {
    staging.deleteRecursively()
    if (backup.exists()) {
        if (activated && target.exists() && !target.deleteRecursively()) {
            throw IOException("Import failed and the activated bundle could not be removed; previous bundle remains in backup.", cause)
        }
        if (!backup.renameTo(target)) {
            throw IOException("Import failed and rollback could not restore the previous bundle; backup remains at ${backup.absolutePath}.", cause)
        }
    } else if (activated && target.exists() && !target.deleteRecursively()) {
        throw IOException("Import failed and the activated bundle could not be removed.", cause)
    }
}

/** Explicitly sets and verifies 0755-style executable access for native runtime payloads. */
internal object RuntimeExecutablePermissions {
    fun ensureExecutable(file: File, relativePath: String) {
        if (!file.isFile) throw IOException("Runtime executable is missing after extraction: $relativePath")
        if (!file.setExecutable(true, false) && !file.canExecute()) throw IOException("Unable to set executable flag for $relativePath.")
        if (!file.canExecute()) throw IOException("Runtime executable permission verification failed for $relativePath.")
    }
}

/** Pure reserve threshold used by import preflight and regression tests. */
internal fun runtimeBundleHasReserve(availableBytes: Long, reserveBytes: Long): Boolean = reserveBytes >= 0 && availableBytes >= reserveBytes
