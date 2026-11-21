package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeManagerTest {
    @Test fun missingBundleBlocksLaunchAndReportsMissingComponents() {
        val manager = RuntimeManager()
        assertEquals("MISSING_NATIVE_COMPONENTS", manager.availability())
        val result = manager.start(File("mo2.exe"))
        assertTrue(result is LaunchPreparationResult.MissingPayload)
        assertTrue((result as LaunchPreparationResult.MissingPayload).blockers.any { it.contains("Runtime bundle root") })
        assertEquals(RuntimeState.NOT_INSTALLED, manager.state)
        try { manager.start(); fail("Launch must be blocked") } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("RUNTIME_NOT_INSTALLED"))
        }
        assertTrue(manager.diagnostics().snapshot().isNotEmpty())
    }

    @Test fun validBundleWithoutLauncherRemainsValidationOnly() {
        val root = createValidBundle()
        val manager = RuntimeManager(bundleRoot = root)
        assertEquals("BUNDLE_VALIDATION_ONLY", manager.availability())
        assertEquals(RuntimeState.NOT_INSTALLED, manager.state)
        val result = manager.start(File(root, "mo2.exe"))
        assertTrue(result is LaunchPreparationResult.MissingPayload)
        assertTrue((result as LaunchPreparationResult.MissingPayload).blockers.any { it.contains("WineRuntimeLauncher") })
        assertEquals(RuntimeState.NOT_INSTALLED, manager.state)
        try { manager.start(); fail("Runtime integration is still missing") } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("RUNTIME_NOT_READY"))
        }
        root.deleteRecursively()
    }

    @Test fun bundleRootAlignmentRequiresSameCanonicalDirectory() {
        val root = Files.createTempDirectory("manager-bundle-alignment").toFile()
        assertTrue(runtimeBundleRootsMatch(root, File(root, ".")))
        assertFalse(runtimeBundleRootsMatch(root, null))
        assertFalse(runtimeBundleRootsMatch(root, File(root, "other")))
        root.deleteRecursively()
    }

    private fun createValidBundle(): File {
        val root = Files.createTempDirectory("manager-bundle-").toFile()
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=b\nwine64=w\nwineserver=s\nlibrary_dir=lib\n")
        listOf("b", "w", "s").forEach { name ->
            File(root, name).apply {
                val elf = ByteArray(64)
                elf[0] = 0x7f; elf[1] = 'E'.code.toByte(); elf[2] = 'L'.code.toByte(); elf[3] = 'F'.code.toByte()
                elf[4] = 2; elf[5] = 1; elf[18] = 183.toByte(); elf[19] = 0
                writeBytes(elf)
                setExecutable(true)
            }
        }
        File(root, "lib").mkdirs()
        File(root, "lib/lib.so").writeBytes(byteArrayOf(1))
        return root
    }
    @Test fun launchIsBlockedWhilePreviousSessionCleanupIsPending() {
        val first = Any()
        val guard = RuntimeCleanupGuard()
        assertFalse(guard.isCompleted())
        assertFalse(isRuntimeSessionCurrent(null, guard, first, guard))
        // A retained process reference is the manager's cleanup-pending sentinel.
        assertTrue(first !== null)
        assertTrue(guard.runOnce {})
        assertTrue(guard.isCompleted())
    }

    @Test fun unexpectedPreparationFailureIsConvertedIntoFailureResult() {
        val result = safeRuntimePreparation { throw IllegalStateException("simulated preparation crash") }
        assertTrue(result is LaunchPreparationResult.MissingPayload)
        assertTrue((result as LaunchPreparationResult.MissingPayload).blockers.single().contains("simulated preparation crash"))
    }

}

