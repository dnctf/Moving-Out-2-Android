package com.dnctf.movingout2.runtime

import java.io.File

/** Optional graphics payload checks. Presence is not equivalent to successful driver loading. */
data class GraphicsBundleAssessment(val dxvk: String, val turnip: String, val notes: List<String>)

object RuntimeGraphicsComponents {
    fun assess(root: File): GraphicsBundleAssessment {
        val notes = mutableListOf<String>()
        val props = runCatching { java.util.Properties().apply { File(root, "runtime.properties").inputStream().use(::load) } }.getOrNull()
        fun resolve(key: String): File? {
            val relative = props?.getProperty(key)?.trim().orEmpty()
            if (relative.isBlank()) return null
            val dir = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
            if (!dir.toPath().startsWith(root.canonicalFile.toPath())) { notes += "$key path escapes runtime bundle."; return null }
            return dir
        }
        val dxvkDir = resolve("dxvk_dir")
        val dxvkStatus = when {
            dxvkDir == null -> "NOT_CONFIGURED"
            !dxvkDir.isDirectory || !dxvkDir.canRead() -> "INVALID_DIRECTORY"
            else -> {
                val d3d = File(dxvkDir, "d3d11.dll")
                val dxgi = File(dxvkDir, "dxgi.dll")
                if (isX64Pe(d3d) && isX64Pe(dxgi)) "FILES_PRESENT_UNVERIFIED" else "INCOMPLETE"
            }
        }
        if (dxvkStatus == "FILES_PRESENT_UNVERIFIED") notes += "DXVK DLL payload is present; Wine loading and Vulkan translation are not verified."
        if (dxvkStatus == "INCOMPLETE" || dxvkStatus == "INVALID_DIRECTORY") notes += "DXVK path configured but required x64 d3d11.dll/dxgi.dll payload is missing or invalid."
        val turnipDir = resolve("turnip_dir")
        val turnipStatus = when {
            turnipDir == null -> "NOT_CONFIGURED"
            !turnipDir.isDirectory || !turnipDir.canRead() -> "INVALID_DIRECTORY"
            else -> {
                val files = turnipDir.walkTopDown().maxDepth(8).filter { it.isFile }.toList()
                val driver = files.any { it.extension == "so" && isArm64Elf(it) }
                val icd = files.any { it.extension.equals("json", true) && runCatching { it.readText().take(8192).contains("library_path") }.getOrDefault(false) }
                if (driver && icd) "FILES_PRESENT_UNVERIFIED" else "INCOMPLETE"
            }
        }
        if (turnipStatus == "FILES_PRESENT_UNVERIFIED") notes += "Turnip ARM64 driver and ICD metadata are present; loader integration and GPU execution are not verified."
        if (turnipStatus == "INCOMPLETE" || turnipStatus == "INVALID_DIRECTORY") notes += "Turnip path configured but ARM64 driver .so and ICD JSON are not both valid/present."
        return GraphicsBundleAssessment(dxvkStatus, turnipStatus, notes)
    }

    private fun isX64Pe(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 70) return@runCatching false
        val b = file.inputStream().use { it.readNBytes(4096) }
        if (b.size < 64 || b[0] != 'M'.code.toByte() || b[1] != 'Z'.code.toByte()) return@runCatching false
        val offset = (b[0x3c].toInt() and 255) or ((b[0x3d].toInt() and 255) shl 8) or ((b[0x3e].toInt() and 255) shl 16) or ((b[0x3f].toInt() and 255) shl 24)
        offset >= 0 && offset <= b.size - 6 && b[offset] == 'P'.code.toByte() && b[offset+1] == 'E'.code.toByte() &&
            b[offset+2] == 0.toByte() && b[offset+3] == 0.toByte() && b[offset+4] == 0x64.toByte() && b[offset+5] == 0x86.toByte()
    }.getOrDefault(false)

    private fun isArm64Elf(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 20) return@runCatching false
        val b = file.inputStream().use { it.readNBytes(20) }
        b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[4] == 2.toByte() && b[5] == 1.toByte() && (b[18].toInt() and 255) == 183 && (b[19].toInt() and 255) == 0
    }.getOrDefault(false)
}
