package com.dnctf.movingout2.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WineLaunchCommandTest {
    @Test fun guestSearchPathIncludesWineUnixArchitectureDirectory() {
        val root = Files.createTempDirectory("wine-unix-path").toFile()
        val glibc = File(root, "glibc").apply { mkdirs() }
        val wine = File(root, "wine/lib/wine").apply { mkdirs() }
        val unix = File(wine, "x86_64-unix").apply { mkdirs() }
        val path = WineLaunchCommand.guestLibrarySearchPath(glibc, wine).orEmpty()
        assertTrue(path.startsWith(glibc.absolutePath))
        assertTrue(path.contains(unix.absolutePath))
        assertTrue(path.contains(wine.absolutePath))
        root.deleteRecursively()
    }

    @Test fun environmentKeepsNativeLibraryDirectoryAndAddsTurnipDirectory() {
        val root = Files.createTempDirectory("wine-host-libs").toFile()
        val native = File(root, "native").apply { mkdirs() }
        val turnip = File(root, "turnip").apply { mkdirs() }
        val env = WineLaunchCommand.environment(
            prefix = File(root, "prefix"),
            sharedInput = File(root, "xinput_shm.bin"),
            hostLibraryDirectory = native,
            fpsCap = 60,
            additionalHostLibraryDirectories = listOf(turnip)
        )
        assertTrue(env["LD_LIBRARY_PATH"].orEmpty().contains(native.absolutePath))
        assertTrue(env["LD_LIBRARY_PATH"].orEmpty().contains(turnip.absolutePath))
        root.deleteRecursively()
    }
}
