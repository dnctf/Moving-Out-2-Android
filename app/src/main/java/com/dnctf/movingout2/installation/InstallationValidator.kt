package com.dnctf.movingout2.installation

import java.io.File
import java.io.InputStream

enum class ValidationStage { FOLDER_ACCESS, CORE_FILES, STRUCTURE, VERSION_ARCH, COMPATIBILITY, LAUNCH_READINESS }
enum class InstallationStatus { VALID, INCOMPLETE, ACCESS_ERROR, INVALID_STRUCTURE, INCOMPATIBLE, RUNTIME_NOT_INSTALLED }
data class StageResult(val stage: ValidationStage, val passed: Boolean, val details: List<String> = emptyList())
data class InstallationReport(
    val status: InstallationStatus, val stages: List<StageResult>, val missingFiles: List<String>,
    val accessErrors: List<String>, val detectedArchitecture: String?, val detectedUnityVersion: String?,
    val metadataVersion: Int?, val hardwareBlockers: List<String>, val runtimeStatus: String = "RUNTIME_NOT_INSTALLED"
) { val isReadyForRuntime: Boolean get() = false }

class InstallationValidator {
    private val required = listOf("mo2.exe", "GameAssembly.dll", "UnityPlayer.dll", "baselib.dll",
        "MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat")
    private val peFiles = required.dropLast(1)

    fun validate(root: File, persistablePermission: Boolean = true, hardwareBlockers: List<String> = emptyList()): InstallationReport {
        val missing = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val stageResults = mutableListOf<StageResult>()
        if (!persistablePermission) errors += "Persisted SAF read permission is unavailable or revoked."
        if (!root.exists() || !root.isDirectory || !root.canRead()) errors += "Selected folder is not readable."
        stageResults += StageResult(ValidationStage.FOLDER_ACCESS, errors.isEmpty(), errors.toList())

        val files = mutableMapOf<String, File>()
        for (path in required) {
            val file = findCaseInsensitive(root, path)
            when {
                file == null || !file.isFile -> missing += path
                file.length() <= 0 -> errors += "$path is empty (0 bytes)"
                !file.canRead() -> errors += "$path is not readable"
                else -> files[path] = file
            }
        }
        stageResults += StageResult(ValidationStage.CORE_FILES, missing.isEmpty() && errors.isEmpty(),
            missing.map { "Missing: $it" } + errors)

        val metadataDir = findCaseInsensitive(root, "MovingOut2_Data/il2cpp_data/Metadata")
        val metadataDirOk = metadataDir?.isDirectory == true && metadataDir.canRead()
        if (!metadataDirOk && missing.none { it.endsWith("global-metadata.dat") }) missing += "MovingOut2_Data/il2cpp_data/Metadata/"
        stageResults += StageResult(ValidationStage.STRUCTURE, metadataDirOk,
            if (metadataDirOk) emptyList() else listOf("Metadata directory missing, not a directory, or unreadable"))

        val peChecks = peFiles.map { name -> name to files[name]?.let(::inspectPeArchitecture) }
        val exeArch = peChecks.firstOrNull { it.first == "mo2.exe" }?.second
        val peOk = peChecks.all { it.second == "x86_64" }
        stageResults += StageResult(ValidationStage.VERSION_ARCH, peOk,
            peChecks.map { (name, arch) -> "$name: ${arch ?: "invalid PE header"}" } +
                "Unity version not detected (not parsed)" +
                "Metadata version: ${files[required.last()]?.let(::inspectMetadataVersion) ?: "unknown"})
        stageResults += StageResult(ValidationStage.COMPATIBILITY, hardwareBlockers.isEmpty(), hardwareBlockers.toList())

        val status = when {
            !persistablePermission || !root.canRead() -> InstallationStatus.ACCESS_ERROR
            missing.isNotEmpty() || errors.isNotEmpty() -> InstallationStatus.INCOMPLETE
            !peOk || !metadataDirOk -> InstallationStatus.INVALID_STRUCTURE
            hardwareBlockers.isNotEmpty() -> InstallationStatus.INCOMPATIBLE
            else -> InstallationStatus.VALID
        }
        stageResults += StageResult(ValidationStage.LAUNCH_READINESS, false,
            listOf("Native runtime is not installed. Launch is blocked regardless of file validation."))
        return InstallationReport(status, stageResults, missing.distinct(), errors.distinct(), exeArch, null,
            files[required.last()]?.let(::inspectMetadataVersion), hardwareBlockers.toList())
    }

    private fun findCaseInsensitive(root: File, relative: String): File? {
        var current = root
        for (segment in relative.split('/').filter(String::isNotBlank)) {
            if (!current.isDirectory) return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
        }
        return current
    }

    private fun inspectPeArchitecture(file: File): String? = runCatching {
        file.inputStream().use { input ->
            val b = readUpTo(input, 4096)
            if (b.size < 64 || b[0] != 0x4d.toByte() || b[1] != 0x5a.toByte()) return@runCatching null
            val offset = readUInt32(b, 0x3c) ?: return@runCatching null
            if (offset < 0 || offset + 6 > b.size) return@runCatching null
            if (b[offset] != 0x50.toByte() || b[offset+1] != 0x45.toByte() || b[offset+2] != 0.toByte() || b[offset+3] != 0.toByte()) return@runCatching null
            when (readUInt16(b, offset+4)) { 0x8664 -> "x86_64"; 0x14c -> "x86"; 0xaa64 -> "arm64"; else -> "unknown" }
        }
    }.getOrNull()

    private fun readUInt16(b: ByteArray, o: Int): Int? = if (o < 0 || o+2 > b.size) null else (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)
    private fun readUInt32(b: ByteArray, o: Int): Int? = if (o < 0 || o+4 > b.size) null else (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16) or ((b[o+3].toInt() and 255) shl 24)

    internal fun readUpTo(input: InputStream, size: Int): ByteArray {
        require(size >= 0)
        val output = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(output, offset, size - offset)
            if (n <= 0) break
            offset += n
        }
        return if (offset == size) output else output.copyOf(offset)
    }
    private fun inspectMetadataVersion(file: File): Int? = runCatching {
        file.inputStream().use { input ->
            val b = readUpTo(input, 8)
            if (b.size < 8) null else readUInt32(b, 4)
        }
    }.getOrNull()
}
