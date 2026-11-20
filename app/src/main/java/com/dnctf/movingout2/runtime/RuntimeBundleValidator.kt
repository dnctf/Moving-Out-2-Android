package com.dnctf.movingout2.runtime

import java.io.File
import java.io.FileInputStream
import java.util.Properties

enum class RuntimeBundleStatus { NOT_FOUND, INVALID_MANIFEST, INCOMPLETE, READY }
data class RuntimeBundleReport(
    val status: RuntimeBundleStatus,
    val root: File?,
    val box64: File?,
    val wine64: File?,
    val wineserver: File?,
    val libraryDirectory: File?,
    val blockers: List<String>,
    val manifestVersion: Int? = null
) {
    val isReady: Boolean get() = status == RuntimeBundleStatus.READY
}

/**
 * Validates a user-supplied ARM64 native runtime bundle.
 * This only verifies the bundle layout and ELF headers; it does not prove Wine/Box64 compatibility.
 */
class RuntimeBundleValidator {
    fun validate(root: File): RuntimeBundleReport {
        if (!root.exists() || !root.isDirectory) return report(RuntimeBundleStatus.NOT_FOUND, null, listOf("Runtime bundle directory is not present."))
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
            ?: return report(RuntimeBundleStatus.INVALID_MANIFEST, root, listOf("Unable to resolve runtime bundle directory."))
        val manifest = File(canonicalRoot, "runtime.properties")
        if (!manifest.isFile || !manifest.canRead()) return report(RuntimeBundleStatus.INVALID_MANIFEST, canonicalRoot, listOf("runtime.properties is missing or unreadable."))
        val properties = runCatching { Properties().apply { FileInputStream(manifest).use(::load) } }.getOrElse {
            return report(RuntimeBundleStatus.INVALID_MANIFEST, canonicalRoot, listOf("Manifest could not be parsed: ${it.message ?: it.javaClass.simpleName}"))
        }
        val version = properties.getProperty("format_version")?.toIntOrNull()
        if (version != 1) return report(RuntimeBundleStatus.INVALID_MANIFEST, canonicalRoot, listOf("Unsupported format_version; expected 1."))
        if (properties.getProperty("target_abi") != "arm64-v8a") return report(RuntimeBundleStatus.INVALID_MANIFEST, canonicalRoot, listOf("target_abi must be arm64-v8a."))

        val blockers = mutableListOf<String>()
        fun resolve(key: String): File? {
            val value = properties.getProperty(key)?.trim().orEmpty()
            if (value.isBlank()) { blockers += "Manifest key '$key' is empty."; return null }
            val candidate = runCatching { File(canonicalRoot, value).canonicalFile }.getOrNull()
            if (candidate == null || !candidate.toPath().startsWith(canonicalRoot.toPath())) {
                blockers += "Manifest path '$key' escapes the bundle directory."; return null
            }
            if (!candidate.isFile || candidate.length() <= 0 || !candidate.canRead()) {
                blockers += "Required file '$key' is missing, empty, or unreadable."; return null
            }
            if (!candidate.canExecute()) blockers += "Required executable '$key' is not marked executable."
            if (!isArm64Elf(candidate)) blockers += "Required executable '$key' is not a valid ELF AArch64 binary."
            return candidate
        }
        val box64 = resolve("box64")
        val wine64 = resolve("wine64")
        val wineserver = resolve("wineserver")
        val libPath = properties.getProperty("library_dir")?.trim().orEmpty()
        val libraryDirectory = if (libPath.isBlank()) {
            blockers += "Manifest key 'library_dir' is empty."; null
        } else runCatching { File(canonicalRoot, libPath).canonicalFile }.getOrNull()?.also { dir ->
            if (!dir.toPath().startsWith(canonicalRoot.toPath())) blockers += "library_dir escapes the bundle directory."
            else if (!dir.isDirectory || !dir.canRead() || dir.listFiles().isNullOrEmpty()) blockers += "Library directory is missing, unreadable, or empty."
        }
        return RuntimeBundleReport(if (blockers.isEmpty()) RuntimeBundleStatus.READY else RuntimeBundleStatus.INCOMPLETE,
            canonicalRoot, box64, wine64, wineserver, libraryDirectory, blockers.distinct(), version)
    }

    private fun isArm64Elf(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(20)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read <= 0) return@use false
                offset += read
            }
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() && header[5] == 1.toByte() &&
                ((header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)) == 183
        }
    }.getOrDefault(false)

    private fun report(status: RuntimeBundleStatus, root: File?, blockers: List<String>) =
        RuntimeBundleReport(status, root, null, null, null, null, blockers)
}
