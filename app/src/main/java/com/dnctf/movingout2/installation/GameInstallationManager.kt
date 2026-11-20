package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class SafInstallationReport(val missing: List<String>, val accessErrors: List<String>, val stageSummary: List<String>) {
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
        required.forEach { path ->
            val file = find(root, path)
            when {
                file == null || !file.isFile -> missing += path
                file.length() <= 0L -> errors += "$path is empty (0 bytes)"
                !file.canRead() -> errors += "$path is not readable"
            }
        }
        val metadataDir = find(root, "MovingOut2_Data/il2cpp_data/Metadata")
        val structural = metadataDir?.isDirectory == true
        return SafInstallationReport(missing.distinct(), errors.distinct(), listOf(
            "Folder access: ${if (errors.none { it.contains("cannot be opened") }) "PASS" else "FAIL"}",
            "Core file detection: ${if (missing.isEmpty() && errors.isEmpty()) "PASS" else "INCOMPLETE"}",
            "Structural validation: ${if (structural) "PASS" else "FAIL"}",
            "Version/architecture: NOT YET INSPECTED",
            "Compatibility: NOT YET ASSESSED",
            "Launch readiness: ${if (missing.isEmpty() && errors.isEmpty() && structural) "FILES_PRESENT; RUNTIME_NOT_INSTALLED" else "BLOCKED"}"
        ))
    }

    private fun find(root: DocumentFile, path: String): DocumentFile? = runCatching {
        var current = root
        path.split('/').filter(String::isNotBlank).forEach { segment ->
            current = current.listFiles().firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return@runCatching null
        }
        current
    }.getOrNull()
}