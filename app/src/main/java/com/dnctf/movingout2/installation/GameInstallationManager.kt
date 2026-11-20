package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class SafInstallationReport(val missing: List<String>, val accessErrors: List<String>, val stageSummary: List<String>, val detectedUnityVersion: String? = null, val metadataVersion: Int? = null, val architecture: String? = null) {
    val isComplete: Boolean get() = missing.isEmpty() && accessErrors.isEmpty()
}

class GameInstallationManager(private val context: Context) {
    private val required = listOf("mo2.exe", "GameAssembly.dll", "UnityPlayer.dll", "baselib.dll",
        "MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat")

    fun validate(uri: Uri): SafInstallationReport {
        val errors = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!persisted) errors += "Persistable SAF read permission is not held."
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (root == null || !root.isDirectory || !root.canRead()) {
            errors += "Selected tree cannot be opened/read."
            return SafInstallationReport(required, errors, listOf("Folder access: FAIL", "Core file detection: NOT RUN", "Structural validation: NOT RUN", "Version/architecture: NOT RUN", "Compatibility: NOT RUN", "Launch readiness: BLOCKED"))
        }
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
        val metadataDir = find(root, "MovingOut2_Data/il2cpp_data/Metadata")
        val structural = metadataDir?.isDirectory == true
        val globalManagers = find(root, "MovingOut2_Data/globalgamemanagers")
        val unityVersion = globalManagers?.let { read(it, 256 * 1024) }?.let(UnityGameProfileDetector::detectUnityVersion)
        val metadata = resolved[required.last()]?.let { read(it, 8) }?.let(UnityGameProfileDetector::detectMetadataVersion)
        val architecture = resolved["mo2.exe"]?.let { read(it, 4096) }?.let(UnityGameProfileDetector::detectPeArchitecture)
        val corePass = missing.isEmpty() && errors.isEmpty()
        val profilePass = architecture == "x86_64" && unityVersion != null && metadata != null
        val launchFiles = corePass && structural && architecture == "x86_64"
        return SafInstallationReport(missing.distinct(), errors.distinct(), listOf(
            "Folder access: ${if (persisted && root.canRead()) "PASS" else "FAIL"}",
            "Core file detection: ${if (missing.isEmpty() && errors.isEmpty()) "PASS" else "INCOMPLETE"}",
            "Structural validation: ${if (structural) "PASS" else "FAIL"}",
            "Version/architecture: ${architecture ?: "not detected"}",
            "Unity version: ${unityVersion ?: "not detected"}",
            "IL2CPP metadata version: ${metadata ?: "not detected"}",
            "Unity profile confidence: ${if (profilePass) "PARTIAL" else "LOW"} (file evidence only; compatibility not proven)",
            "Compatibility: NOT YET ASSESSED",
            "Launch readiness: ${if (launchFiles) "FILES_PRESENT; RUNTIME_NOT_INSTALLED" else "BLOCKED"}"
        ), unityVersion, metadata, architecture)
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