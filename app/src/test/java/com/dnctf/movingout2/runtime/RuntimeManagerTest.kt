package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeManagerTest {
    @Test fun missingBundleBlocksLaunchAndReportsMissingComponents() {
        val manager = RuntimeManager()
        assertEquals("MISSING_NATIVE_COMPONENTS", manager.availability())
        try { manager.start(); fail("Launch must be blocked") } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("RUNTIME_NOT_INSTALLED"))
        }
        assertTrue(manager.diagnostics().snapshot().isNotEmpty())
    }
    @Test fun validBundleIsNotMistakenForInstalledRuntime() {
        val root = Files.createTempDirectory("manager-bundle-").toFile()
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=b\nwine64=w\nwineserver=s\nlibrary_dir=lib\n")
        listOf("b","w","s").forEach { name ->
            File(root, name).apply {
                val elf = ByteArray(64)
                elf[0] = 0x7f; elf[1] = 'E'.code.toByte(); elf[2] = 'L'.code.toByte(); elf[3] = 'F'.code.toByte()
                elf[4] = 2; elf[5] = 1; elf[18] = 183.toByte(); elf[19] = 0
                writeBytes(elf)
                setExecutable(true)
            }
        }
        File(root,"lib").mkdirs(); File(root,"lib/lib.so").writeBytes(byteArrayOf(1))
        val manager = RuntimeManager(bundleRoot = root)
        assertEquals("BUNDLE_VALIDATION_ONLY", manager.availability())
        assertEquals(RuntimeState.NOT_INSTALLED, manager.state)
        try { manager.start(); fail("Runtime integration is still missing") } catch (e: UnsupportedOperationException) { }
        root.deleteRecursively()
    }
}
