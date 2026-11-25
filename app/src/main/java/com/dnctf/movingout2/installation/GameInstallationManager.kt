package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dnctf.movingout2.compatibility.GameBinaryAnalyzer

data class SafInstallationReport(
    val missing: List<String>,
    val accessErrors: List<String>,
    val stageSummary: List<String>,
    val detectedUnityVersion: String? = null,
    val metadataVersion: Int? = null,
    val architecture: String? = null,
    val staticImports: Map<String, Set<String>> = emptyMap(),
    val importErrors: Map<String, List<String>> = emptyMap()
) {
    val isComplete: Boolean get() = missing.isEmpty() && accessErrors.isEmpty() &&
        architecture == "x86_64" && metadataVersion != null
}

class GameInstallationManager(private val context: Context) {
    private val peFiles = listOf("mo2.exe", "GameAssembly.dll", "UnityPlayer.dll", "baselib.dll")

    fun validate(uri: Uri): SafInstallationReport {
        val errors = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!persisted) errors += "Persistable SAF read permission is not held (permission may have been revoked)."
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (root == null || !root.isDirectory || !root.canRead()) {
            errors += "Selected tree cannot be opened/read."
            return SafInstallationReport(peFiles + UnityDataDirectoryLocator.metadataFilePath("mo2.exe"), errors, listOf(
                "Folder access: FAIL", "Core file detection: NOT RUN", "Structural validation: NOT RUN",
                "Version/architecture: NOT RUN", "Compatibility: NOT_ASSESSED", "Launch readiness: BLOCKED"
            ))
        }

        val executable = find(root, "mo2.exe")
        val metadataRelative = UnityDataDirectoryLocator.metadataFilePath(executable?.name ?: "mo2.exe")
        val required = peFiles + metadataRelative
        val resolved = mutableMapOf<String, DocumentFile>()
        required.forEach { path ->
            val file = find(root, path)
            when {
                file == null || !file.isFile -> missing += path
                file.length() <= 0L -> errors += "$path is empty (0 bytes)"
                !file.canRead() -> errors += "$path is not readable"
                else -> resolved[path] = file
            }
        }
        // Compute this stage before metadata-header and PE architecture checks, matching filesystem validation.
        val corePass = InstallationStagePolicy.coreFilesPassed(missing, errors, persisted && root.canRead())
        val metadataDir = find(root, UnityDataDirectoryLocator.metadataPath(executable?.name ?: "mo2.exe"))
        val metadataDirOk = metadataDir?.isDirectory == true && metadataDir.canRead()
        val metadataBytes = resolved[required.last()]?.let { read(it, 8) }
        val metadataVersion = metadataBytes?.let(UnityGameProfileDetector::detectMetadataVersion)
        if (metadataDirOk.not()) missing.add("${UnityDataDirectoryLocator.metadataPath(executable?.name ?: "mo2.exe")}/")
        if (metadataVersion == null && required.last() !in missing) errors += "IL2CPP global-metadata.dat header is invalid or truncated."

        val peResults = peFiles.map { path ->
            path to resolved[path]?.let { file -> read(file, 64 * 1024)?.let(UnityGameProfileDetector::detectPeArchitecture) }
        }
        val architecture = peResults.firstOrNull { it.first == "mo2.exe" }?.second
        val allPeX64 = peResults.all { it.second == "x86_64" }
        val importAnalyses = peFiles.mapNotNull { path ->
            resolved[path]?.let { file ->
                path to runCatching {
                    context.contentResolver.openInputStream(file.uri)?.use { stream -> GameBinaryAnalyzer.analyze(stream) }
                        ?: GameBinaryAnalyzer.analyze(byteArrayOf())
                }.getOrElse { GameBinaryAnalyzer.Analysis(-1, false, emptySet(), listOf(it.message ?: "Import analysis failed")) }
            }
        }.toMap()
        val staticImports = importAnalyses.mapValues { it.value.imports }
        val importErrors = importAnalyses.mapNotNull { (path, analysis) ->
            analysis.errors.takeIf { it.isNotEmpty() }?.let { path to it }
        }.toMap()
        val globalManagers = find(root, UnityDataDirectoryLocator.globalManagersPath(executable?.name ?: "mo2.exe"))
        val unityVersion = globalManagers?.let { read(it, 256 * 1024) }?.let(UnityGameProfileDetector::detectUnityVersion)
        val structurePass = metadataDirOk
        val versionPass = allPeX64 && metadataVersion != null
        val stageSummary = listOf(
            "Folder access: ${if (persisted && root.canRead()) "PASS" else "FAIL"}",
            "Core file detection: ${if (corePass) "PASS" else "INCOMPLETE"}",
            "Structural validation: ${if (structurePass) "PASS" else "FAIL"}",
            "Version/architecture: ${peResults.joinToString { "${it.first}=${it.second ?: "INVALID"}" }}; Unity: ${unityVersion ?: "not detected"}; IL2CPP: ${metadataVersion ?: "invalid/unknown"}",
            "Compatibility: NOT_ASSESSED (device/GPU/runtime capabilities must be checked separately)",
            "Launch readiness: BLOCKED (native runtime is not installed/validated)"
        )
        // Persisted SAF access, core files, structure, PE32+ x64, and IL2CPP header
        // must all pass before reporting installation complete. Runtime is never implied.
        if (!persisted) errors += "SAF permission revoked or not persisted."
        if (!allPeX64) errors += "One or more required PE files are invalid or not x86_64 PE32+."
        return SafInstallationReport(missing.distinct(), errors.distinct(), stageSummary, unityVersion, metadataVersion, architecture, staticImports, importErrors)
    }

    private fun read(file: DocumentFile, limit: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(file.uri)?.use { UnityGameProfileDetector.readBounded(it, limit) }
    }.getOrNull()

    private fun find(root: DocumentFile, path: String): DocumentFile? = runCatching {
        var current = root
        path.split('/').filter(String::isNotBlank).forEach { segment ->
            current = current.listFiles().firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return@runCatching null
        }
        current
    }.getOrNull()
}
