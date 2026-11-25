package com.dnctf.movingout2.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeSettingsConsumerTest {

    @Test
    fun scalesResolutionEvenly() {
        val consumer = RuntimeSettingsConsumer()
        val settings = LauncherSettings(resolutionScale = 75)
        val config = consumer.consume(settings, 1920, 1080)

        assertEquals(1440, config.effectiveWidth)
        assertEquals(810, config.effectiveHeight)
        assertEquals(1440, config.launchOptions.width)
        assertEquals(810, config.launchOptions.height)
    }

    @Test
    fun mapsLowSnapdragonPresetToConservativeDynarecAndMinimalHud() {
        val consumer = RuntimeSettingsConsumer()
        val settings = LauncherSettings(
            resolutionScale = 70,
            fpsTarget = 30,
            graphicsPreset = "Low / Snapdragon 855"
        )
        val config = consumer.consume(settings, 1920, 1080)

        assertEquals(30, config.launchOptions.fpsCap)
        assertEquals("30", config.environmentOverrides["DXVK_FRAME_RATE"])
        assertEquals("fps", config.environmentOverrides["DXVK_HUD"])
        assertEquals("1", config.environmentOverrides["BOX64_DYNAREC_BIGBLOCK"])
        assertEquals("1", config.environmentOverrides["BOX64_DYNAREC_SAFEFLAGS"])
        assertEquals("1", config.environmentOverrides["BOX64_DYNAREC_FASTNAN"])
        assertEquals("1", config.environmentOverrides["BOX64_DYNAREC_FASTROUND"])
    }

    @Test
    fun mapsHighPresetToAggressiveDynarecAndFullHud() {
        val consumer = RuntimeSettingsConsumer()
        val settings = LauncherSettings(
            resolutionScale = 100,
            fpsTarget = 60,
            graphicsPreset = "High / newer Snapdragon"
        )
        val config = consumer.consume(settings, 1920, 1080)

        assertEquals(60, config.launchOptions.fpsCap)
        assertEquals("60", config.environmentOverrides["DXVK_FRAME_RATE"])
        assertEquals("devinfo,fps,frametimes,drawcalls", config.environmentOverrides["DXVK_HUD"])
        assertEquals("2", config.environmentOverrides["BOX64_DYNAREC_BIGBLOCK"])
        assertEquals("2", config.environmentOverrides["BOX64_DYNAREC_SAFEFLAGS"])
        assertEquals("1", config.environmentOverrides["BOX64_DYNAREC_X87DOUBLE"])
    }

    @Test
    fun passesTurnipAndDxvkPathsWhenProvided() {
        val tempDir = java.nio.file.Files.createTempDirectory("consumer-test").toFile()
        try {
            val icd = File(tempDir, "freedreno_icd.json").apply { writeText("{}") }
            val dxvk = File(tempDir, "dxvk").apply { mkdirs() }

            val consumer = RuntimeSettingsConsumer()
            val config = consumer.consume(LauncherSettings(), 1920, 1080, turnipIcdFile = icd, dxvkDirectory = dxvk)

            assertTrue(config.launchOptions.useTurnip)
            assertTrue(config.launchOptions.useDxvk)
            assertEquals(icd.absolutePath, config.environmentOverrides["VK_ICD_FILENAMES"])
            assertEquals(dxvk.absolutePath, config.environmentOverrides["DXVK_DLL_PATH"])
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
