package com.dnctf.movingout2.runtime

import java.io.File

object WineLaunchCommand {
    fun toWineZPath(path: String): String = "Z:" + path.replace('/', '\\')

    fun arguments(winePath: String, windowsGamePath: String, width: Int, height: Int): List<String> =
        listOf(winePath, windowsGamePath, "-force-d3d11", "-screen-width", width.toString(),
            "-screen-height", height.toString(), "-screen-fullscreen", "1")

    /**
     * LD_LIBRARY_PATH is exclusively for ARM64 host-side libraries.
     * Box64 guest search paths are opt-in and must come from ABI-validated x86-64 directories.
     */
    fun environment(
        prefix: File,
        sharedInput: File,
        hostLibraryDirectory: File,
        fpsCap: Int,
        turnipIcd: File? = null,
        dxvkDirectory: File? = null,
        guestLibraryDirectory: File? = null,
        guestBinaryDirectory: File? = null
    ): Map<String, String> {
        val result = linkedMapOf(
            "WINEPREFIX" to prefix.absolutePath,
            "WINEDLLOVERRIDES" to "d3d11,dxgi,xinput1_3,xinput1_4=n,b",
            "MO2_XINPUT_SHM_PATH" to toWineZPath(sharedInput.absolutePath),
            "BOX64_DYNAREC" to "1",
            "BOX64_DYNAREC_BIGBLOCK" to "1",
            "BOX64_DYNAREC_STRONGMEM" to "1",
            "BOX64_DYNAREC_SAFEFLAGS" to "1",
            "DXVK_STATE_CACHE" to "1",
            "DXVK_FRAME_RATE" to fpsCap.toString(),
            "LD_LIBRARY_PATH" to hostLibraryDirectory.absolutePath
        )
        guestLibraryDirectory?.takeIf { it.isDirectory && it.canRead() }?.let {
            result["BOX64_LD_LIBRARY_PATH"] = it.absolutePath
        }
        guestBinaryDirectory?.takeIf { it.isDirectory && it.canRead() }?.let {
            result["BOX64_PATH"] = it.absolutePath
        }
        if (turnipIcd != null) result["VK_ICD_FILENAMES"] = turnipIcd.absolutePath
        if (dxvkDirectory != null) result["DXVK_DLL_PATH"] = dxvkDirectory.absolutePath
        return result
    }

    fun winebootArguments(winePath: String): List<String> =
        listOf(winePath, "wineboot", "--init")

    fun wineserverArguments(wineserverPath: String): List<String> =
        listOf(wineserverPath, "-f", "-p")

    fun wineserverKillArguments(wineserverPath: String): List<String> =
        listOf(wineserverPath, "-k")

    fun wineserverProbeArguments(wineserverPath: String): List<String> =
        listOf(wineserverPath, "-k0")
}
