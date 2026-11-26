package com.dnctf.movingout2.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WineLaunchCommandTest {
    @Test fun environmentUsesWineBundlePathsAndKeepsHostLoaderPathNativeOnly() {
        val root = Files.createTempDirectory("wine-env-test").toFile()
        try {
            val wineRoot = File(root, "wine").apply { mkdirs() }
            val wineBin = File(wineRoot, "bin").apply { mkdirs() }
            val wineLib = File(wineRoot, "lib/wine").apply { mkdirs() }
            val guestGlibc = File(wineRoot, "lib/x86_64-linux-gnu").apply { mkdirs() }
            val hostLibs = File(root, "lib/arm64-v8a").apply { mkdirs() }
            val env = WineLaunchCommand.environment(
                prefix = File(root, "prefix"),
                sharedInput = File(root, "xinput_shm.bin"),
                hostLibraryDirectory = hostLibs,
                fpsCap = 30,
                guestLibraryDirectory = wineLib,
                guestGlibcDirectory = guestGlibc,
                guestBinaryDirectory = wineBin
            )
            assertEquals(wineLib.absolutePath, env["WINEDLLPATH"])
            assertEquals(File(wineBin, "wine64").absolutePath, env["WINELOADER"])
            assertEquals(File(wineBin, "wineserver").absolutePath, env["WINESERVER"])
            assertEquals(guestGlibc.absolutePath + ":" + wineLib.absolutePath, env["BOX64_LD_LIBRARY_PATH"])
            assertEquals(hostLibs.absolutePath, env["LD_LIBRARY_PATH"])
            assertTrue(env["LD_LIBRARY_PATH"]!!.contains("arm64-v8a"))
        } finally {
            root.deleteRecursively()
        }
    }
}
