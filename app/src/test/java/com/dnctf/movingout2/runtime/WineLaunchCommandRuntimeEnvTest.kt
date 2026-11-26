package com.dnctf.movingout2.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineLaunchCommandRuntimeEnvTest {
    @Test
    fun movingOut2EnvironmentIncludesRequiredBox64WineAndAudioFlags() {
        val root = java.nio.file.Files.createTempDirectory("mo2-launch-env").toFile()
        val prefix = File(root, "prefix")
        val input = File(prefix, "xinput_shm.bin")
        val hostLib = File(root, "host-arm64")
        val guestLib = File(root, "wine/lib/wine")
        val glibc = File(root, "wine/lib/x86_64-linux-gnu")
        val guestBin = File(root, "wine/bin")
        listOf(hostLib, guestLib, glibc, guestBin).forEach { check(it.mkdirs() || it.isDirectory) }

        try {
            val env = WineLaunchCommand.environment(
                prefix = prefix,
                sharedInput = input,
                hostLibraryDirectory = hostLib,
                fpsCap = 30,
                guestLibraryDirectory = guestLib,
                guestGlibcDirectory = glibc,
                guestBinaryDirectory = guestBin,
                dxvkDirectory = File(root, "dxvk/x64").apply { mkdirs() }
            )

            assertEquals("win64", env["WINEARCH"])
            assertEquals(prefix.absolutePath, env["WINEPREFIX"])
            assertTrue(env["WINEDLLOVERRIDES"].orEmpty().contains("d3d11=n,b"))
            assertTrue(env["WINEDLLOVERRIDES"].orEmpty().contains("dxgi=n,b"))
            assertTrue(env["WINEDLLOVERRIDES"].orEmpty().contains("steam_api64=n"))
            assertTrue(env["WINEDLLOVERRIDES"].orEmpty().contains("xinput9_1_0=n,b"))
            assertEquals("1", env["BOX64_DYNAREC_STRONGMEM"])
            assertEquals("1", env["BOX64_DYNAREC_SAFEFLAGS"])
            assertEquals("1", env["BOX64_DYNAREC_BIGBLOCK"])
            assertEquals("1", env["BOX64_DYNAREC_CALLRET"])
            assertEquals("0", env["BOX64_AVX"])
            assertEquals("60", env["PULSE_LATENCY_MSEC"])
            assertEquals("1", env["DXVK_STATE_CACHE"])
            assertEquals(guestLib.absolutePath, env["WINEDLLPATH"])
            assertEquals("${glibc.absolutePath}:${guestLib.absolutePath}", env["BOX64_LD_LIBRARY_PATH"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun winebootUsesExplicitInitializationArguments() {
        assertEquals(listOf("/runtime/wine64", "wineboot", "--init"),
            WineLaunchCommand.winebootArguments("/runtime/wine64"))
    }

    @Test
    fun gameWorkingDirectoryCanBeResolvedFromExecutableParent() {
        val gameRoot = File("build/test-game-root").apply { mkdirs() }
        val gameExe = File(gameRoot, "mo2.exe")
        try {
            assertEquals(gameRoot.absolutePath, gameExe.parentFile?.absolutePath)
            assertEquals("Z:" + gameExe.absolutePath.replace('/', '\\'),
                WineLaunchCommand.toWineZPath(gameExe.absolutePath))
        } finally {
            gameRoot.deleteRecursively()
        }
    }
}
