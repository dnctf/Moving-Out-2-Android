package com.dnctf.movingout2.settings

import com.dnctf.movingout2.runtime.WineLaunchOptions
import java.io.File

data class ConsumedLaunchConfiguration(
    val launchOptions: WineLaunchOptions,
    val environmentOverrides: Map<String, String>,
    val effectiveWidth: Int,
    val effectiveHeight: Int
)

class RuntimeSettingsConsumer {
    fun consume(
        settings: LauncherSettings,
        baseWidth: Int = 1920,
        baseHeight: Int = 1080,
        turnipIcdFile: File? = null,
        dxvkDirectory: File? = null
    ): ConsumedLaunchConfiguration {
        val scale = settings.resolutionScale.coerceIn(50, 100) / 100f
        val scaledW = ((baseWidth * scale).toInt() / 2) * 2
        val scaledH = ((baseHeight * scale).toInt() / 2) * 2

        val fps = if (settings.fpsTarget in listOf(30, 45, 60)) settings.fpsTarget else 30
        val preset = settings.graphicsPreset
        val isLow = preset.contains("Low", ignoreCase = true) || preset.contains("855", ignoreCase = true)
        val isHigh = preset.contains("High", ignoreCase = true)

        val env = mutableMapOf<String, String>()
        env["DXVK_FRAME_RATE"] = fps.toString()
        env["DXVK_STATE_CACHE"] = "1"

        // DXVK HUD profiling settings
        when {
            isHigh -> env["DXVK_HUD"] = "devinfo,fps,frametimes,drawcalls"
            isLow -> env["DXVK_HUD"] = "fps"
            else -> env["DXVK_HUD"] = "fps,frametimes"
        }

        // Box64 Dynarec optimizations tuned for Qualcomm Snapdragon
        env["BOX64_DYNAREC"] = "1"
        env["BOX64_DYNAREC_STRONGMEM"] = "1"
        if (isLow) {
            env["BOX64_DYNAREC_BIGBLOCK"] = "1"
            env["BOX64_DYNAREC_SAFEFLAGS"] = "1"
            env["BOX64_DYNAREC_FASTNAN"] = "1"
            env["BOX64_DYNAREC_FASTROUND"] = "1"
        } else if (isHigh) {
            env["BOX64_DYNAREC_BIGBLOCK"] = "2"
            env["BOX64_DYNAREC_SAFEFLAGS"] = "2"
            env["BOX64_DYNAREC_FASTNAN"] = "1"
            env["BOX64_DYNAREC_FASTROUND"] = "1"
            env["BOX64_DYNAREC_X87DOUBLE"] = "1"
        } else {
            env["BOX64_DYNAREC_BIGBLOCK"] = "1"
            env["BOX64_DYNAREC_SAFEFLAGS"] = "1"
        }

        if (turnipIcdFile != null && turnipIcdFile.isFile) {
            env["VK_ICD_FILENAMES"] = turnipIcdFile.absolutePath
        }
        if (dxvkDirectory != null && dxvkDirectory.isDirectory) {
            env["DXVK_DLL_PATH"] = dxvkDirectory.absolutePath
        }

        val options = WineLaunchOptions(
            width = scaledW,
            height = scaledH,
            fpsCap = fps,
            useTurnip = turnipIcdFile != null && turnipIcdFile.isFile,
            useDxvk = dxvkDirectory != null && dxvkDirectory.isDirectory,
            environmentOverrides = env.toMap()
        )

        return ConsumedLaunchConfiguration(options, env, scaledW, scaledH)
    }
}
