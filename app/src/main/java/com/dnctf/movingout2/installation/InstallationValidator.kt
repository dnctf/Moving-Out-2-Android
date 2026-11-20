package com.dnctf.movingout2.installation

import java.io.File

enum class ValidationStage { FOLDER_ACCESS, CORE_FILES, STRUCTURE, VERSION_ARCH, COMPATIBILITY, LAUNCH_READINESS }
enum class InstallationStatus { VALID, INCOMPLETE, ACCESS_ERROR, INVALID_STRUCTURE, INCOMPATIBLE, RUNTIME_NOT_INSTALLED }

data class StageResult(val stage: ValidationStage, val passed: Boolean, val details: List<String> = emptyList())
data class InstallationReport(
    val status: InstallationStatus,
    val stages: List<StageResult>,
    val missingFiles: List<String>,
    val accessErrors: List<String>,
    val detectedArchitecture: String?,
    val detectedUnityVersion: String?,
    val metadataVersion: Int?,
    val hardwareBlockers: List<String>,
    val runtimeStatus: String = "RUNTIME_NOT_INSTALLED"
) {
    val isReadyForRuntime: Boolean get() = status == InstallationStatus.VALID && runtimeStatus != "RUNTIME_NOT_INSTALLED"
}

class InstallationValidator {
    private val required = listOf("mo2.exe", "GameAssembly.dll", "UnityPlayer.dll", "baselib.dll",
        "MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat")

    fun validate(root: File, persistablePermission: Boolean = true, hardwareBlockers: List<String> = emptyList()): InstallationReport {
        val stages = mutableListOf<StageResult>()
        val missing = mutableListOf<String>()
        val errors = mutableListOf<String>()
        if (!persistablePermission) errors += "Persisted SAF read permission is unavailable or revoked."
        if (!root.exists() || !root.isDirectory || !root.canRead()) errors += "Selected folder is not readable."
        stages += StageResult(ValidationStage.FOLDER_ACCESS, errors.isEmpty(), errors.toList())

        required.forEach { path ->
            val file = findCaseInsensitive(root, path)
            when {
                file == null -> missing += path
                !file.isFile -> missing += path
                file.length() <= 0L -> errors += "$path is empty (0 bytes)"
                !file.canRead() -> errors += "$path is not readable"
            }
        }
        stages += StageResult(ValidationStage.CORE_FILES, missing.isEmpty() && errors.isEmpty(),
            (missing.map { "Missing: $it" } + errors).toList())

        val metadataParent = findCaseInsensitive(root, "MovingOut2_Data/il2cpp_data/Metadata")
        val structuralOk = metadataParent?.isDirectory == true
        if (!structuralOk && "MovingOut2_Data/il2cpp_data/Metadata" !in missing) missing += "MovingOut2_Data/il2cpp_data/Metadata/"
        stages += StageResult(ValidationStage.STRUCTURE, structuralOk, if (structuralOk) emptyList() else listOf("Metadata directory missing"))

        val exe = findCaseInsensitive(root, "mo2.exe")
        val arch = exe?.let { inspectPeArchitecture(it) }
        val unityVersion = null // Unity version cannot be reliably inferred from arbitrary player binary without a validated parser.
        val meta = findCaseInsensitive(root, required.last())
        val metadataVersion = meta?.let { inspectMetadataVersion(it) }
        stages += StageResult(ValidationStage.VERSION_ARCH, arch == "x86_64",
            listOf("PE architecture: ${arch ?: "unknown"}", "Unity version: not detected", "Metadata version: ${metadataVersion ?: "unknown"}"))
        stages += StageResult(ValidationStage.COMPATIBILITY, hardwareBlockers.isEmpty(),
            hardwareBlockers.toList())

        val hasErrors = errors.isNotEmpty()
        val status = when {
            !persistablePermission || !root.canRead() -> InstallationStatus.ACCESS_ERROR
            missing.isNotEmpty() -> InstallationStatus.INCOMPLETE
            arch != "x86_64" -> InstallationStatus.INVALID_STRUCTURE
            hardwareBlockers.isNotEmpty() -> InstallationStatus.INCOMPATIBLE
            else -> InstallationStatus.VALID
        }
        stages += StageResult(ValidationStage.LAUNCH_READINESS, status == InstallationStatus.VALID,
            listOf("Game files validated; native runtime is not installed."))
        return InstallationReport(status, stages, missing.distinct(), errors.distinct(), arch, unityVersion, metadataVersion, hardwareBlockers.toList())
    }

    private fun findCaseInsensitive(root: File, relative: String): File? {
        var current = root
        relative.split('/').filter { it.isNotBlank() }.forEach { segment ->
            if (!current.isDirectory) return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
        }
        return current
    }

    private fun inspectPeArchitecture(file: File): String? = runCatching {
        file.inputStream().use { input ->
            val bytes = input.readNBytes(4096)
            if (bytes.size < 64 || bytes[0] != 0x4d.toByte() || bytes[1] != 0x5a.toByte()) return@runCatching null
            val peOffset = (bytes[0x3c].toInt() and 0xff) or ((bytes[0x3d].toInt() and 0xff) shl 8) or
                ((bytes[0x3e].toInt() and 0xff) shl 16) or ((bytes[0x3f].toInt() and 0xff) shl 24)
            if (peOffset < 0 || peOffset + 6 > bytes.size) return@runCatching null
            if (bytes[peOffset] != 0x50.toByte() || bytes[peOffset + 1] != 0x45.toByte() ||
                bytes[peOffset + 2] != 0.toByte() || bytes[peOffset + 3] != 0.toByte()) return@runCatching null
            val machine = (bytes[peOffset + 4].toInt() and 0xff) or ((bytes[peOffset + 5].toInt() and 0xff) shl 8)
            when (machine) { 0x8664 -> "x86_64"; 0x14c -> "x86"; 0xaa64 -> "arm64"; else -> "unknown" }
        }
    }.getOrNull()

    private fun inspectMetadataVersion(file: File): Int? = runCatching {
        file.inputStream().use { input ->
            val bytes = input.readNBytes(8)
            if (bytes.size < 8) null else (bytes[4].toInt() and 0xff) or ((bytes[5].toInt() and 0xff) shl 8) or
                ((bytes[6].toInt() and 0xff) shl 16) or ((bytes[7].toInt() and 0xff) shl 24)
        }
    }.getOrNull()
}