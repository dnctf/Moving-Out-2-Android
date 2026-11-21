package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeLaunchConfigTest {
    @Test fun sharedMemoryPathIsPrivateAndEnvironmentPreservesBase() {
        val root = File(System.getProperty("java.io.tmpdir"), "mo2-runtime-config-test")
        val config = RuntimeLaunchConfig(root)
        val env = config.environment(mapOf("BASE" to "value"))
        assertEquals("value", env["BASE"])
        assertEquals(config.sharedMemoryFile.absolutePath, env["MO2_XINPUT_SHM_PATH"])
        assertEquals("xinput_shm.bin", config.sharedMemoryFile.name)
        root.deleteRecursively()
    }
    @Test fun parentPreparationCreatesOnlyTheRequestedDirectory() {
        val root = File(System.getProperty("java.io.tmpdir"), "mo2-input-path-test")
        root.deleteRecursively()
        val file = RuntimeLaunchConfig(root).prepareSharedMemoryParent()
        assertTrue(root.isDirectory)
        assertEquals(File(root, "xinput_shm.bin"), file)
        root.deleteRecursively()
    }
}
