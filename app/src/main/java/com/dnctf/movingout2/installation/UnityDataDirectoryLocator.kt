package com.dnctf.movingout2.installation

/**
 * Resolves Unity's *_Data directory from the actual game executable name.
 * Moving Out 2 ships mo2.exe + mo2_Data (not MovingOut2_Data).
 */
internal object UnityDataDirectoryLocator {
    fun directoryName(executableName: String): String {
        val baseName = executableName.substringAfterLast('/').substringBeforeLast('.', executableName).trim()
        require(baseName.isNotBlank() && baseName.none { it == '/' || it == '\\\\' }) {
            "Invalid game executable name."
        }
        return "${baseName}_Data"
    }

    fun metadataPath(executableName: String): String =
        "${directoryName(executableName)}/il2cpp_data/Metadata"

    fun metadataFilePath(executableName: String): String =
        "${metadataPath(executableName)}/global-metadata.dat"

    fun globalManagersPath(executableName: String): String =
        "${directoryName(executableName)}/globalgamemanagers"
}
