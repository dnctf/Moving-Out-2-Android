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
        assertEquals("d3d11,dxgi,xinput1_3,xinput1_4=n,b", env["WINEDLLOVERRIDES"])
        assertEquals("Z:\\xinput_shm.bin", env["MO2_XINPUT_SHM_PATH"])
        assertEquals("1", env["BOX64_DYNAREC_STRONGMEM"])
        assertEquals("45", env["DXVK_FRAME_RATE"])
        assertEquals("/turnip/icd.json", env["VK_ICD_FILENAMES"])
    }

    @Test fun environmentOmitsTurnipWhenDisabled() {
        val env = WineLaunchCommand.environment(File("/prefix"), File("/shm"), File("/lib"), 60)
        assertFalse(env.containsKey("VK_ICD_FILENAMES"))
    }
}
