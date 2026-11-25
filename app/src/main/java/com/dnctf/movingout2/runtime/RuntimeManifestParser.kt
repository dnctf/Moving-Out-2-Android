package com.dnctf.movingout2.runtime

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RuntimeManifestValidation(
    val valid: Boolean,
    val errors: List<String>,
    val manifest: JSONObject?,
    val componentFiles: Map<String, File>
)

class RuntimeManifestParser {
    fun validate(root: File): RuntimeManifestValidation {
        val errors = mutableListOf<String>()
        val files = linkedMapOf<String, File>()
        val manifestFile = File(root, "runtime-manifest.json")
        if (!manifestFile.isFile || manifestFile.length() == 0L || !manifestFile.canRead()) {
            return RuntimeManifestValidation(false, listOf("runtime-manifest.json missing/empty/unreadable"), null, files)
        }
        val json = try { JSONObject(manifestFile.readText()) } catch (error: Exception) {
            return RuntimeManifestValidation(false, listOf("Manifest JSON parse failed: ${error.message}"), null, files)
        }
        if (json.optInt("schemaVersion", -1) != 2) errors += "schemaVersion must be 2 (explicit component ABI/provenance required)"
        if (json.optString("targetAbi") != "arm64-v8a") errors += "targetAbi must be arm64-v8a"
        val provenance = json.optJSONObject("provenance")
        if (provenance == null || provenance.optString("packager") != "scripts/package_runtime_bundle.sh" ||
            provenance.optString("manifestFormat") != "role-validated-abi-v2") {
            errors += "Manifest provenance is missing or unsupported."
        }
        val components = json.optJSONObject("components") ?: run { errors += "components object missing"; JSONObject() }

        fun verify(key: String, fallback: String, required: Boolean, expectedAbi: String, directory: Boolean = false) {
            val component = components.optJSONObject(key)
            if (component == null || !component.optBoolean("included", false)) {
                if (required) errors += "Required component $key missing or marked excluded"
                return
            }
            val declaredAbi = component.optString("abi")
            if (declaredAbi != expectedAbi) {
                errors += "Component $key ABI must be declared as $expectedAbi (found ${declaredAbi.ifBlank { "missing" }})"
                return
            }
            val path = component.optString("path", fallback)
            val hash = component.optString("sha256").lowercase()
            if (path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains(':') ||
                path.split('/', '\\').any { it == ".." }) {
                errors += "Unsafe path for $key"; return
            }
            if (!hash.matches(Regex("[a-f0-9]{64}"))) { errors += "Invalid SHA-256 for $key"; return }
            val file = runCatching { File(root, path).canonicalFile }.getOrNull()
            if (file == null || !file.toPath().startsWith(root.canonicalFile.toPath())) {
                errors += "Path escapes bundle for $key"; return
            }
            if (directory) {
                if (!file.isDirectory || file.walkTopDown().none { it.isFile && it.length() > 0 }) {
                    errors += "Directory missing/empty: $key"; return
                }
            } else if (!file.isFile || file.length() <= 0) { errors += "File missing/empty: $key"; return }
            val actual = if (directory) hashDirectory(file) else hashFile(file)
            if (actual != hash) { errors += "SHA-256 mismatch: $key"; return }
            files[key] = file
        }

        verify("box64", "box64/bin/box64", true, RuntimeBinaryInspector.ELF64_AARCH64)
        verify("wine64", "wine/bin/wine64", true, RuntimeBinaryInspector.ELF64_X86_64)
        verify("wineserver", "wine/bin/wineserver", true, RuntimeBinaryInspector.ELF64_X86_64)
        verify("wineLib", "wine/lib/wine", true, "DIRECTORY_SHA256_X86_64", directory = true)
        verify("d3d11", "dxvk/x64/d3d11.dll", true, RuntimeBinaryInspector.PE32_PLUS_X86_64)
        verify("dxgi", "dxvk/x64/dxgi.dll", true, RuntimeBinaryInspector.PE32_PLUS_X86_64)
        verify("xinput1_3", "wine/lib/wine/x86_64-windows/xinput1_3.dll", true, RuntimeBinaryInspector.PE32_PLUS_X86_64)
        verify("xinput1_4", "wine/lib/wine/x86_64-windows/xinput1_4.dll", true, RuntimeBinaryInspector.PE32_PLUS_X86_64)
        if (components.optJSONObject("turnip")?.optBoolean("included", false) == true) {
            verify("turnipSo", "turnip/libvulkan_freedreno.so", true, RuntimeBinaryInspector.ELF64_AARCH64_SHARED)
            verify("turnipIcd", "turnip/freedreno_icd.aarch64.json", true, "JSON")
        }
        return RuntimeManifestValidation(errors.isEmpty(), errors.distinct(), json, files)
    }

    private fun hashDirectory(dir: File): String {
        val listing = StringBuilder()
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach {
            listing.append(hashFile(it)).append("  ./").append(it.relativeTo(dir).invariantSeparatorsPath).append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(listing.toString().toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
