package com.dnctf.movingout2.runtime

import org.json.JSONObject
import java.io.File
import java.util.Properties

/** Optional graphics payload checks. Presence is not equivalent to successful driver loading. */
data class GraphicsBundleAssessment(val dxvk: String, val turnip: String, val notes: List<String>)

object RuntimeGraphicsComponents {
    fun assess(root: File): GraphicsBundleAssessment {
        val notes = mutableListOf<String>()
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
            ?: return GraphicsBundleAssessment("UNKNOWN", "UNKNOWN", listOf("Runtime root cannot be resolved."))
        val manifest = runCatching {
            JSONObject(File(canonicalRoot, "runtime-manifest.json").readText())
        }.getOrNull()
        val components = manifest?.optJSONObject("components")
        val props = runCatching {
            Properties().apply { File(canonicalRoot, "runtime.properties").inputStream().use(::load) }
        }.getOrNull()

        fun manifestFile(key: String): File? {
            val component = components?.optJSONObject(key) ?: return null
            if (!component.optBoolean("included", false)) return null
            val relative = component.optString("path").trim()
            if (relative.isBlank() || relative.startsWith("/") || relative.contains(':') ||
                relative.split('/', '\\\\').any { it == ".." }) {
                notes += "$key manifest path is invalid."
                return null
            }
            val file = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
            if (file == null || !file.toPath().startsWith(canonicalRoot.toPath())) {
                notes += "$key manifest path escapes runtime root."
                return null
            }
            return file
        }

        fun legacyFile(key: String): File? {
            val relative = props?.getProperty(key)?.trim().orEmpty()
            if (relative.isBlank()) return null
            val file = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull() ?: return null
            if (!file.toPath().startsWith(canonicalRoot.toPath())) {
                notes += "$key path escapes runtime bundle."
                return null
            }
            return file
        }

        val d3d11 = manifestFile("d3d11") ?: legacyFile("dxvk_dir")?.let { File(it, "d3d11.dll") }
        val dxgi = manifestFile("dxgi") ?: legacyFile("dxvk_dir")?.let { File(it, "dxgi.dll") }
        val dxvkStatus = when {
            d3d11 == null && dxgi == null -> "NOT_CONFIGURED"
            d3d11?.let(::isX64Pe) == true && dxgi?.let(::isX64Pe) == true -> "FILES_PRESENT_UNVERIFIED"
            else -> "INCOMPLETE"
        }
        if (dxvkStatus == "FILES_PRESENT_UNVERIFIED") notes +=
            "DXVK x64 DLLs are present; Wine loading and Vulkan translation are not verified."
        if (dxvkStatus == "INCOMPLETE") notes +=
            "DXVK manifest entries exist but one or more x64 DLLs are missing or invalid."

        val turnipSo = manifestFile("turnipSo") ?: legacyFile("turnip_dir")?.walkTopDown()
            ?.maxDepth(8)?.firstOrNull { it.isFile && it.extension == "so" && isArm64Elf(it) }
        val turnipIcd = manifestFile("turnipIcd") ?: legacyFile("turnip_dir")?.walkTopDown()
            ?.maxDepth(8)?.firstOrNull { it.isFile && it.extension.equals("json", true) }
        val turnipStatus = when {
            turnipSo == null && turnipIcd == null -> "NOT_BUNDLED"
            turnipSo?.let(::isArm64Elf) == true && turnipIcd?.isFile == true &&
                runCatching { readBoundedText(turnipIcd, 8192).contains("library_path") }.getOrDefault(false) ->
                "BUNDLE_AVAILABLE_NOT_SELECTED"
            else -> "INCOMPLETE"
        }
        if (turnipStatus == "BUNDLE_AVAILABLE_NOT_SELECTED") notes +=
            "Turnip ARM64 userspace driver and ICD metadata are bundled but intentionally not force-loaded; verify device compatibility first."
        if (turnipStatus == "INCOMPLETE") notes +=
            "Turnip bundle is incomplete or its ARM64 driver/ICD metadata is invalid."
        return GraphicsBundleAssessment(dxvkStatus, turnipStatus, notes)
    }

    private fun readBoundedText(file: File, maxBytes: Int): String = file.inputStream().use { input ->
        val buffer = ByteArray(maxBytes)
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n <= 0) break
            offset += n
        }
        String(buffer, 0, offset, Charsets.UTF_8)
    }

    private fun isX64Pe(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 70) return@runCatching false
        val b = file.inputStream().use { stream ->
            val bytes = ByteArray(4096)
            var offset = 0
            while (offset < bytes.size) { val n = stream.read(bytes, offset, bytes.size - offset); if (n <= 0) break; offset += n }
            if (offset == bytes.size) bytes else bytes.copyOf(offset)
        }
        if (b.size < 64 || b[0] != 'M'.code.toByte() || b[1] != 'Z'.code.toByte()) return@runCatching false
        val offset = (b[0x3c].toInt() and 255) or ((b[0x3d].toInt() and 255) shl 8) or
            ((b[0x3e].toInt() and 255) shl 16) or ((b[0x3f].toInt() and 255) shl 24)
        offset >= 0 && offset <= b.size - 6 && b[offset] == 'P'.code.toByte() &&
            b[offset + 1] == 'E'.code.toByte() && b[offset + 2] == 0.toByte() &&
            b[offset + 3] == 0.toByte() && b[offset + 4] == 0x64.toByte() && b[offset + 5] == 0x86.toByte()
    }.getOrDefault(false)

    private fun isArm64Elf(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 20) return@runCatching false
        val b = file.inputStream().use { stream ->
            val bytes = ByteArray(20)
            var offset = 0
            while (offset < bytes.size) { val n = stream.read(bytes, offset, bytes.size - offset); if (n <= 0) break; offset += n }
            if (offset == bytes.size) bytes else bytes.copyOf(offset)
        }
        b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() &&
            b[3] == 'F'.code.toByte() && b[4] == 2.toByte() && b[5] == 1.toByte() &&
            (b[18].toInt() and 255) == 183 && (b[19].toInt() and 255) == 0
    }.getOrDefault(false)
}
