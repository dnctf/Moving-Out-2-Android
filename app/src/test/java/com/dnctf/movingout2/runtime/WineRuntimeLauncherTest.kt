package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WineRuntimeLauncherTest {
    @Test fun convertsAndroidPathToWineZPath() {
        assertEquals("Z:\\data\\user\\0\\com.example\\files\\games\\active\\mo2.exe",
            WineLaunchCommand.toWineZPath("/data/user/0/com.example/files/games/active/mo2.exe"))
    }

    @Test fun buildsBox64WineGameArgumentsInOrder() {
        val args = WineLaunchCommand.arguments("/runtime/box64", "Z:\\games\\mo2.exe", 1920, 1080)
        assertEquals(listOf("/runtime/box64", "Z:\\games\\mo2.exe", "-force-d3d11", "-screen-width", "1920", "-screen-height", "1080", "-screen-fullscreen", "1"), args)
    }

    @Test fun includesRuntimeEnvironmentAndOptionalTurnip() {
        val env = WineLaunchCommand.environment(File("/prefix"), File("/xinput_shm.bin"), File("/bundle/lib"), 45, File("/turnip/icd.json"), File("/dxvk"))
        assertEquals("/prefix", env["WINEPREFIX"])
        assertEquals("d3d11,dxgi=n,b;xinput1_3,xinput1_4,xinput9_1_0=n,b;steam_api64=b", env["WINEDLLOVERRIDES"])
        assertEquals("Z:\\xinput_shm.bin", env["MO2_XINPUT_SHM_PATH"])
        assertEquals(File("/bundle/lib").absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals("1", env["BOX64_DYNAREC_STRONGMEM"])
        assertEquals("45", env["DXVK_FRAME_RATE"])
        assertEquals("/turnip/icd.json", env["VK_ICD_FILENAMES"])
    }

    @Test fun dxvkCanBeDisabledWithoutDisablingXinputOverrides() {
        val env = WineLaunchCommand.environment(File("/prefix"), File("/shm"), File("/lib"), 60,
            dxvkDirectory = File("/dxvk"), useDxvk = false)
        assertEquals("d3d11,dxgi=b;xinput1_3,xinput1_4,xinput9_1_0=n,b;steam_api64=b", env["WINEDLLOVERRIDES"])
        assertFalse(env.containsKey("DXVK_DLL_PATH"))
    }

    @Test fun diagnosticHudIsOptIn() {
        val env = WineLaunchCommand.environment(File("/prefix"), File("/shm"), File("/lib"), 60,
            dxvkDirectory = File("/dxvk"), diagnosticHud = true)
        assertEquals("fps,devinfo", env["DXVK_HUD"])
    }

    @Test fun environmentOmitsTurnipWhenDisabled() {
        val env = WineLaunchCommand.environment(File("/prefix"), File("/shm"), File("/lib"), 60)
        assertFalse(env.containsKey("VK_ICD_FILENAMES"))
    }
    @Test fun guestGlibcPrecedesWineLibrariesInBox64SearchPath() {
        val root = java.nio.file.Files.createTempDirectory("guest-glibc-path").toFile()
        val glibc = File(root, "wine/lib/x86_64-linux-gnu").apply { mkdirs() }
        val wineLib = File(root, "wine/lib/wine").apply { mkdirs() }
        File(wineLib, "x86_64-unix").mkdirs()
        val host = File(root, "host-arm64").apply { mkdirs() }
        val env = WineLaunchCommand.environment(File(root, "prefix"), File(root, "shm"), host, 30,
            guestLibraryDirectory = wineLib, guestGlibcDirectory = glibc)
        assertEquals(glibc.absolutePath + ":" + File(wineLib, "x86_64-unix").absolutePath + ":" + wineLib.absolutePath, env["BOX64_LD_LIBRARY_PATH"])
        assertEquals(host.absolutePath, env["LD_LIBRARY_PATH"])
        assertFalse(env["LD_LIBRARY_PATH"]!!.contains("x86_64-linux-gnu"))
        root.deleteRecursively()
    }
    @Test fun neverCopiesHostArm64LibraryPathIntoBox64GuestVariables() {
        val host = File("/host/arm64/lib")
        val env = WineLaunchCommand.environment(File("/prefix"), File("/shm"), host, 60)
        assertEquals(host.absolutePath, env["LD_LIBRARY_PATH"])
        assertFalse(env.containsKey("BOX64_LD_LIBRARY_PATH"))
        assertFalse(env.containsKey("BOX64_PATH"))
    }

    @Test fun addsBox64PathsOnlyForExistingGuestDirectories() {
        val root = java.nio.file.Files.createTempDirectory("box64-paths").toFile()
        val host = File(root, "host-arm64").apply { mkdirs() }
        val guestLib = File(root, "guest-x86_64/lib").apply { mkdirs() }
        val guestBin = File(root, "guest-x86_64/bin").apply { mkdirs() }
        val env = WineLaunchCommand.environment(File(root, "prefix"), File(root, "shm"), host, 30,
            guestLibraryDirectory = guestLib, guestBinaryDirectory = guestBin)
        assertEquals(host.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals(guestLib.absolutePath, env["BOX64_LD_LIBRARY_PATH"])
        assertEquals(guestBin.absolutePath, env["BOX64_PATH"])
        root.deleteRecursively()
    }

}
