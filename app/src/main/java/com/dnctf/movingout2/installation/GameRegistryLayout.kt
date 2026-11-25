package com.dnctf.movingout2.installation

import java.io.File

/** Shared case-insensitive Unity data-folder resolution for registry discovery and metadata. */
internal object GameRegistryLayout {
    fun executable(root: File, expectedName: String = "mo2.exe"): File? =
        findChild(root, expectedName)?.takeIf { it.isFile }

    fun dataDirectory(root: File, executableName: String = "mo2.exe"): File? {
        val exe = executable(root, executableName) ?: return null
        return findChild(root, UnityDataDirectoryLocator.directoryName(exe.name))?.takeIf { it.isDirectory }
    }

    fun metadataFile(root: File, executableName: String): File =
        resolveCaseInsensitive(root, UnityDataDirectoryLocator.metadataFilePath(executableName))

    fun globalManagersFile(root: File, executableName: String): File =
        resolveCaseInsensitive(root, UnityDataDirectoryLocator.globalManagersPath(executableName))

    private fun resolveCaseInsensitive(root: File, relativePath: String): File {
        var current = root
        for (segment in relativePath.split('/')) {
            current = findChild(current, segment) ?: File(current, segment)
        }
        return current
    }

    private fun findChild(parent: File, name: String): File? =
        parent.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
