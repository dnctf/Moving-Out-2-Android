package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
        val root = createTempDir(prefix = "manager-bundle-")
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=b\nwine64=w\nwineserver=s\nlibrary_dir=lib\n")
        listOf("b","w","s").forEach { name ->
            File(root, name).apply {
                writeBytes(byteArrayOf(0x7f,69,76,70,0,0,0,0,0,0,0,0,0,0,0,0,0,183.toByte(),0))
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
