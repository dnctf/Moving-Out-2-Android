package com.dnctf.movingout2.installation

import java.io.File
import java.io.InputStream
import java.io.IOException
import com.dnctf.movingout2.runtime.RuntimeBundleValidator

enum class ValidationStage { FOLDER_ACCESS, CORE_FILES, STRUCTURE, VERSION_ARCH, COMPATIBILITY, LAUNCH_READINESS }
enum class InstallationStatus { VALID, INCOMPLETE, ACCESS_ERROR, INVALID_STRUCTURE, INCOMPATIBLE, RUNTIME_NOT_INSTALLED }
data class StageResult(val stage: ValidationStage, val passed: Boolean, val details: List<String> = emptyList())

/** Shared policy so filesystem and SAF validators report the core-file stage consistently. */
object InstallationStagePolicy {
    fun coreFilesPassed(missing: List<String>, errors: List<String>, folderAccessPassed: Boolean): Boolean =
        folderAccessPassed && missing.isEmpty() && errors.isEmpty()
}
data class InstallationReport(
    val status: InstallationStatus, val stages: List<StageResult>, val missingFiles: List<String>,
    val accessErrors: List<String>, val detectedArchitecture: String?, val detectedUnityVersion: String?,
    val metadataVersion: Int?, val hardwareBlockers: List<String>, val runtimeStatus: String = "RUNTIME_NOT_INSTALLED"
) {
    val isReadyForRuntime: Boolean
        get() = status == InstallationStatus.VALID &&
            runtimeStatus == "READY" &&
            hardwareBlockers.isEmpty() &&
            listOf(ValidationStage.FOLDER_ACCESS, ValidationStage.CORE_FILES, ValidationStage.STRUCTURE,
                ValidationStage.VERSION_ARCH, ValidationStage.LAUNCH_READINESS)
                .all { required -> stages.any { it.stage == required && it.passed } }
}

class InstallationValidator {
    private val requiredGameLibraries = listOf("GameAssembly.dll", "UnityPlayer.dll", "baselib.dll")
    private val metadataMagic = byteArrayOf(0xaf.toByte(), 0x1b, 0xb1.toByte(), 0xfa.toByte())

    fun validate(root: File, persistablePermission: Boolean = true, hardwareBlockers: List<String> = emptyList(), runtimeBundleRoot: File? = null): InstallationReport {
        val missing = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val stages = mutableListOf<StageResult>()
        val rootReadable = runCatching { root.isDirectory && root.canRead() }.getOrDefault(false)
        if (!persistablePermission) errors += "Persisted SAF read permission is unavailable or revoked."
        if (!rootReadable) errors += "Selected folder is not readable."
        stages += StageResult(ValidationStage.FOLDER_ACCESS, persistablePermission && rootReadable, errors.toList())

        val executable = findUnityGameExecutable(root)
        val executableName = executable?.name ?: "mo2.exe"
        val metadataRelative = UnityDataDirectoryLocator.metadataFilePath(executableName)
        val required = listOf(executableName) + requiredGameLibraries + metadataRelative
        val files = mutableMapOf<String, File>()
        for (path in required) {
            val file = try { findCaseInsensitive(root, path) } catch (e: IOException) {
                errors += "$path: ${e.message ?: "unsafe path"}"
                null
            }
            when {
                file == null || !file.isFile -> missing += path
                file.length() <= 0 -> errors += "$path is empty (0 bytes)"
                !file.canRead() -> errors += "$path is not readable"
                else -> files[path] = file
            }
        }
        stages += StageResult(ValidationStage.CORE_FILES, InstallationStagePolicy.coreFilesPassed(missing, errors, persistablePermission && rootReadable),
            missing.map { "Missing: $it" } + errors.toList())

        val metadataDir = try { findCaseInsensitive(root, UnityDataDirectoryLocator.metadataPath(executable?.name ?: "mo2.exe")) } catch (e: IOException) {
            errors += "Metadata directory: ${e.message ?: "unsafe path"}"
            null
        }
        val metadataDirOk = metadataDir?.isDirectory == true && metadataDir.canRead() && isWithinRoot(root, metadataDir)
        if (!metadataDirOk && missing.none { it.endsWith("global-metadata.dat") }) missing += "${UnityDataDirectoryLocator.metadataPath(executable?.name ?: "mo2.exe")}/"
        stages += StageResult(ValidationStage.STRUCTURE, metadataDirOk,
            if (metadataDirOk) emptyList() else listOf("Metadata directory missing, not a directory, unreadable, or outside selected root"))

        val peNames = listOf(executableName) + requiredGameLibraries
        val peChecks = peNames.map { name -> name to files[name]?.let(::inspectPeArchitecture) }
        val exeArch = peChecks.firstOrNull { it.first.equals(executableName, ignoreCase = true) }?.second
        val peOk = executable != null && peChecks.all { it.second == "x86_64" }
        val metadataVersion = files[required.last()]?.let(::inspectMetadataVersion)
        val unityVersion = runCatching { findCaseInsensitive(root, UnityDataDirectoryLocator.globalManagersPath(executable?.name ?: "mo2.exe")) }
            .getOrNull()?.takeIf { it.isFile && it.canRead() && it.length() > 0 }
            ?.let { file -> runCatching { file.inputStream().use { UnityGameProfileDetector.detectUnityVersion(readUpTo(it, 256 * 1024)) } }.getOrNull() }
        val versionArchPassed = peOk && metadataVersion != null
        stages += StageResult(ValidationStage.VERSION_ARCH, versionArchPassed,
            peChecks.map { (name, arch) -> "$name: ${arch ?: "invalid/truncated PE32+ header"}" } +
                "Unity version: ${unityVersion ?: "not detected (globalgamemanagers unavailable/unsupported)"}" +
                "IL2CPP metadata version: ${metadataVersion ?: "invalid/unknown"}")
        val compatibilityPass = false
        stages += StageResult(ValidationStage.COMPATIBILITY, compatibilityPass,
            hardwareBlockers.toList() + "Compatibility/device capability assessment is not provided by this filesystem validator.")
        val status = when {
            !persistablePermission || !rootReadable -> InstallationStatus.ACCESS_ERROR
            missing.isNotEmpty() || errors.isNotEmpty() -> InstallationStatus.INCOMPLETE
            !peOk || !metadataDirOk || metadataVersion == null -> InstallationStatus.INVALID_STRUCTURE
            hardwareBlockers.isNotEmpty() -> InstallationStatus.INCOMPATIBLE
            else -> InstallationStatus.VALID
        }
        val runtimeReport = runtimeBundleRoot?.let { RuntimeBundleValidator().validate(it) }
        val runtimeReady = runtimeReport?.isReady == true
        stages += StageResult(ValidationStage.LAUNCH_READINESS, runtimeReady,
            if (runtimeReady) emptyList() else runtimeReport?.blockers
                ?: listOf("Runtime bundle was not supplied for integrity validation."))
        val runtimeStatus = when {
            runtimeReady -> "READY"
            runtimeReport == null -> "RUNTIME_NOT_INSTALLED"
            else -> "RUNTIME_INVALID: " + runtimeReport.blockers.joinToString("; ")
        }
        return InstallationReport(status, stages, missing.distinct(), errors.distinct(), exeArch, unityVersion,
            metadataVersion, hardwareBlockers.toList(), runtimeStatus)
    }

    /** Detect a Unity Windows player using the executable + matching *_Data directory pair. */
    internal fun findUnityGameExecutable(root: File): File? {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val unityPlayer = runCatching { findCaseInsensitive(base, "UnityPlayer.dll") }.getOrNull()
        val gameAssembly = runCatching { findCaseInsensitive(base, "GameAssembly.dll") }.getOrNull()
        if (unityPlayer?.isFile != true || gameAssembly?.isFile != true) return null
        return base.listFiles()?.asSequence()
            ?.filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            ?.filterNot { it.name.equals("UnityCrashHandler64.exe", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.firstOrNull { candidate ->
                runCatching { findCaseInsensitive(base, UnityDataDirectoryLocator.directoryName(candidate.name))?.isDirectory == true }
                    .getOrDefault(false)
            }
    }

    private fun findCaseInsensitive(root: File, relative: String): File? {
        if (relative.split('/').any { it.isBlank() || it == "." || it == ".." }) throw IOException("Unsafe relative path.")
        val base = root.canonicalFile
        var current = base
        for (segment in relative.split('/')) {
            if (!current.isDirectory) return null
            current = current.listFiles()?.firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
            if (!isWithinRoot(base, current)) throw IOException("Resolved path escapes selected game root.")
        }
        return current
    }

    private fun isWithinRoot(root: File, target: File): Boolean {
        val base = root.canonicalFile.toPath()
        val resolved = target.canonicalFile.toPath()
        return resolved == base || resolved.startsWith(base)
    }

    private fun inspectPeArchitecture(file: File): String? = runCatching {
        file.inputStream().use { input ->
            val b = readUpTo(input, 64 * 1024)
            if (b.size < 64 || b[0] != 0x4d.toByte() || b[1] != 0x5a.toByte()) return@use null
            val offset = readUInt32(b, 0x3c) ?: return@use null
            if (offset < 0 || offset > b.size - 26) return@use null
            if (b[offset] != 0x50.toByte() || b[offset+1] != 0x45.toByte() || b[offset+2] != 0.toByte() || b[offset+3] != 0.toByte()) return@use null
            val machine = readUInt16(b, offset+4) ?: return@use null
            val optionalSize = readUInt16(b, offset+20) ?: return@use null
            val optionalOffset = offset + 24
            if (optionalSize < 2 || optionalOffset > b.size - 2 || optionalOffset + optionalSize > b.size) return@use null
            val optionalMagic = readUInt16(b, optionalOffset) ?: return@use null
            when {
                machine == 0x8664 && optionalMagic == 0x20b -> "x86_64"
                machine == 0x14c && optionalMagic == 0x10b -> "x86"
                machine == 0xaa64 && optionalMagic == 0x20b -> "arm64"
                else -> null
            }
        }
    }.getOrNull()

    private fun readUInt16(b: ByteArray, o: Int): Int? = if (o < 0 || o > b.size - 2) null else
        (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)
    private fun readUInt32(b: ByteArray, o: Int): Int? = if (o < 0 || o > b.size - 4) null else
        (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or ((b[o+2].toInt() and 255) shl 16) or ((b[o+3].toInt() and 255) shl 24)

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
            if (b.size < 8 || !b.copyOfRange(0, 4).contentEquals(metadataMagic)) null else readUInt32(b, 4)
        }
    }.getOrNull()
}
