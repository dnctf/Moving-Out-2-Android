package com.dnctf.movingout2.runtime

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class RuntimeManifestValidation(
    val valid: Boolean,
    val errors: List<String>,
    val manifest: JSONObject?,
    val componentFiles: Map<String, File>
)

/** Strict validator for MO2 Runtime Bundle v1 manifest and on-disk payload integrity. */
class RuntimeManifestParser {
    fun validate(root: File): RuntimeManifestValidation {
        val errors = mutableListOf<String>()
        val files = linkedMapOf<String, File>()
        val manifestFile = File(root, "runtime-manifest.json")
        if (!manifestFile.isFile || manifestFile.length() <= 0 || !manifestFile.canRead()) {
            return RuntimeManifestValidation(false, listOf("runtime-manifest.json is missing, empty, or unreadable."), null, files)
        }
        val json = try { JSONObject(manifestFile.readText()) } catch (e: Exception) {
            return RuntimeManifestValidation(false, listOf("Manifest JSON parse failed: ${e.message}"), null, files)
        }
        if (json.optInt("schemaVersion", -1) != 1) errors += "schemaVersion must be 1."
        if (json.optString("targetAbi") != "arm64-v8a") errors += "targetAbi must be arm64-v8a."
        val components = json.optJSONObject("components") ?: run { errors += "components object is missing."; JSONObject() }

        fun verify(key: String, expectedPath: String, required: Boolean) {
            val component = components.optJSONObject(key)
            if (component == null || (component.optBoolean("included", true).not() && !required)) {
                if (required) errors += "Required component '$key' is missing."
                return
            }
            val path = component.optString("path", expectedPath)
            val hash = component.optString("sha256").lowercase()
            if (path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.split('/', '\\').any { it == ".." } || path.contains(':')) {
                errors += "Unsafe relative path for component '$key'."
                return
            }
            if (!hash.matches(Regex("[a-f0-9]{64}"))) { errors += "Invalid SHA-256 for component '$key'."; return }
            val candidate = runCatching { File(root, path).canonicalFile }.getOrNull()
            if (candidate == null || !candidate.toPath().startsWith(root.canonicalFile.toPath())) { errors += "Component '$key' escapes bundle root."; return }
            if (!candidate.isFile || candidate.length() <= 0) { errors += "Component '$key' missing or empty."; return }
            val actual = sha256(candidate)
            if (actual != hash) { errors += "SHA-256 mismatch for component '$key'."; return }
            files[key] = candidate
        }

        verify("box64", "box64/bin/box64", true)
        verify("wine64", "wine/bin/wine64", true)
        verify("wineserver", "wine/bin/wineserver", true)
        verify("wineLib", "wine/lib/wine", true)
        verify("d3d11", "dxvk/x64/d3d11.dll", true)
        verify("dxgi", "dxvk/x64/dxgi.dll", true)
        val turnip = components.optJSONObject("turnip")
        if (turnip?.optBoolean("included", false) == true) {
            verify("turnipSo", "turnip/libvulkan_freedreno.so", false)
            verify("turnipIcd", "turnip/freedreno_icd.aarch64.json", false)
        }
        return RuntimeManifestValidation(errors.isEmpty(), errors.distinct(), json, files)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
