package com.dnctf.movingout2.runtime

import java.io.File

object WineLaunchCommand {
    /** Guest x86-64 search order: GNU glibc first, then Wine's Unix libraries. */
    fun guestLibrarySearchPath(guestGlibcDirectory: File?, guestWineLibraryDirectory: File?): String? =
        listOfNotNull(
            guestGlibcDirectory?.takeIf { it.isDirectory && it.canRead() },
            guestWineLibraryDirectory?.takeIf { it.isDirectory && it.canRead() }
        ).joinToString(":").takeIf { it.isNotBlank() }

    fun toWineZPath(path: String): String = "Z:" + path.replace('/', '\\')

    fun arguments(
        winePath: String,
        windowsGamePath: String,
        width: Int,
        height: Int,
        playerLogFile: File? = null
    ): List<String> = buildList {
        add(winePath)
        add(windowsGamePath)
        add("-force-d3d11")
        add("-screen-width"); add(width.toString())
        add("-screen-height"); add(height.toString())
        add("-screen-fullscreen"); add("1")
        playerLogFile?.let { add("-logFile"); add(toWineZPath(it.absolutePath)) }
    }

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
        guestGlibcDirectory: File? = null,
        guestBinaryDirectory: File? = null,
        useDxvk: Boolean = true,
        diagnosticHud: Boolean = false,
        additionalHostLibraryDirectory: File? = null,
        diagnosticsDirectory: File? = null
    ): Map<String, String> {
        // Prefer DXVK's native D3D implementation and force the in-runtime XInput
        // bridge. Steam API DLL selection is deliberately not forced to native: the
        // supplied game inventory contained a modified Steam API wrapper.
        val overrides = buildList {
            if (useDxvk) {
                add("d3d11=n,b")
                add("dxgi=n,b")
            } else {
                add("d3d11=b")
                add("dxgi=b")
            }
            add("steam_api64=b")
            add("xinput1_3,xinput1_4,xinput9_1_0=n,b")
        }.joinToString(";")
        val result = linkedMapOf(
            "WINEARCH" to "win64",
            "WINEPREFIX" to prefix.absolutePath,
            "WINEDLLOVERRIDES" to overrides,
            "MO2_XINPUT_SHM_PATH" to toWineZPath(sharedInput.absolutePath),
            "BOX64_DYNAREC" to "1",
            "BOX64_DYNAREC_BIGBLOCK" to "1",
            "BOX64_DYNAREC_STRONGMEM" to "1",
            "BOX64_DYNAREC_SAFEFLAGS" to "1",
            "BOX64_DYNAREC_CALLRET" to "1",
            "BOX64_AVX" to "0",
            "PULSE_LATENCY_MSEC" to "60",
            "DXVK_STATE_CACHE" to "1",
            "DXVK_FRAME_RATE" to fpsCap.coerceIn(15, 240).toString(),
            "LD_LIBRARY_PATH" to listOfNotNull(
                hostLibraryDirectory.takeIf { it.isDirectory },
                additionalHostLibraryDirectory?.takeIf { it.isDirectory }
            ).joinToString(":").ifBlank { hostLibraryDirectory.absolutePath }
        )
        guestLibrarySearchPath(guestGlibcDirectory, guestLibraryDirectory)?.let {
            result["BOX64_LD_LIBRARY_PATH"] = it
        }
        guestLibraryDirectory?.takeIf { it.isDirectory && it.canRead() }?.let {
            result["WINEDLLPATH"] = it.absolutePath
        }
        guestBinaryDirectory?.takeIf { it.isDirectory && it.canRead() }?.let {
            result["BOX64_PATH"] = it.absolutePath
        }
        if (turnipIcd != null) result["VK_ICD_FILENAMES"] = turnipIcd.absolutePath
        if (useDxvk && dxvkDirectory != null) {
            result["DXVK_DLL_PATH"] = dxvkDirectory.absolutePath
            result["DXVK_LOG_LEVEL"] = "info"
            diagnosticsDirectory?.let { result["DXVK_LOG_PATH"] = it.absolutePath }
        }
        if (diagnosticHud && useDxvk) result["DXVK_HUD"] = "fps,devinfo"
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
