package com.dnctf.movingout2.installation

import java.io.File

/** Case-insensitive Unity player discovery by executable + matching *_Data directory. */
internal object GameRegistryLayout {
    fun executable(root: File, expectedName: String? = null): File? {
        if (!root.isDirectory) return null
        if (expectedName != null) return findChild(root, expectedName)?.takeIf { it.isFile }
        val unityPlayer = findChild(root, "UnityPlayer.dll")?.takeIf { it.isFile } ?: return null
        val gameAssembly = findChild(root, "GameAssembly.dll")?.takeIf { it.isFile } ?: return null
        return root.listFiles()?.asSequence()
            ?.filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            ?.filterNot { it.name.equals("UnityCrashHandler64.exe", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.firstOrNull { candidate ->
                val data = findChild(root, UnityDataDirectoryLocator.directoryName(candidate.name))
                data?.isDirectory == true && unityPlayer.isFile && gameAssembly.isFile
            }
    }

    fun dataDirectory(root: File, executableName: String? = null): File? {
        val exe = (if (executableName == null) executable(root) else executable(root, executableName))
            ?: return null
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
