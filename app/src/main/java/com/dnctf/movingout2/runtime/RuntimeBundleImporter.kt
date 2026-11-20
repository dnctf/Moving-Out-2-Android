package com.dnctf.movingout2.runtime

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

data class RuntimeImportResult(val report: RuntimeBundleReport, val copiedFiles: Int, val copiedBytes: Long)

class RuntimeBundleImporter(
    context: Context,
    private val validator: RuntimeBundleValidator = RuntimeBundleValidator(),
    private val maxBytes: Long = 6L * 1024L*1024L*1024L,
    private val maxEntries: Int = 20_000,
    private val maxDepth: Int = 24
) {
    private val resolver = context.contentResolver
    private val parent = File(context.filesDir, "native_runtime")
    private val target = File(parent, "bundle")
    private val staging = File(parent, "bundle_staging")
    private val backup = File(parent, "bundle_backup")

    fun importTree(uri: Uri): RuntimeImportResult {
        val source = DocumentFile.fromTreeUri(resolverContext(), uri)
        if (source == null || !source.isDirectory || !source.canRead()) throw IOException("Selected SAF tree is not readable.")
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("Unable to create private runtime storage.")
        // Recover a previous import interrupted between moving the active bundle and activating staging.
        if (!target.exists() && backup.exists() && !backup.renameTo(target)) throw IOException("Unable to recover previous runtime bundle.")
        if (target.exists()) backup.deleteRecursively()
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Unable to create runtime staging directory.")
        val counter = Counter()
        var activated = false
        try {
            copyDirectory(source, staging, 0, counter)
            grantExecutableFlags(staging)
            val validation = validator.validate(staging)
            if (!validation.isReady) throw IOException("Runtime bundle failed validation: " + validation.blockers.joinToString("; "))
            if (target.exists() && !target.renameTo(backup)) throw IOException("Unable to preserve existing runtime bundle.")
            if (!staging.renameTo(target)) throw IOException("Unable to activate staged runtime bundle.")
            activated = true
            val active = validator.validate(target)
            if (!active.isReady) throw IOException("Installed bundle revalidation failed: " + active.blockers.joinToString("; "))
            backup.deleteRecursively()
            return RuntimeImportResult(active, counter.files, counter.bytes)
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (backup.exists()) {
                if (activated) target.deleteRecursively()
                backup.renameTo(target)
            } else if (activated) target.deleteRecursively()
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
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            counter.bytes += read.toLong()
                            if (counter.bytes > maxBytes) throw IOException("Runtime bundle exceeds configured import size limit.")
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
        val properties = Properties()
        File(root, "runtime.properties").inputStream().use { properties.load(it) }
        for (key in listOf("box64", "wine64", "wineserver")) {
            val relative = properties.getProperty(key)?.trim().orEmpty()
            if (relative.isBlank()) continue
            val file = File(root, relative).canonicalFile
            if (!file.toPath().startsWith(root.canonicalFile.toPath())) throw IOException("Executable path escapes bundle.")
            if (file.isFile && !file.setExecutable(true, false) && !file.canExecute()) throw IOException("Unable to set executable permission for $key.")
        }
    }
}
