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
    @Test fun cleanupGuardRetriesAfterExceptionAndLocksAfterSuccess() {
        val guard = RuntimeCleanupGuard()
        try {
            guard.runOnce { throw IllegalStateException("cleanup failed") }
            fail("Expected cleanup exception")
        } catch (expected: IllegalStateException) {
            assertEquals("cleanup failed", expected.message)
        }
        assertFalse(guard.isCompleted())
        var calls = 0
        assertTrue(guard.runOnce { calls++ })
        assertEquals(1, calls)
        assertTrue(guard.isCompleted())
        assertFalse(guard.runOnce { calls++ })
        assertEquals(1, calls)
    }


    @Test fun cleanupGuardConcurrentCallersExecuteCleanupAtMostOnce() {
        val guard = RuntimeCleanupGuard()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val firstResult = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val secondResult = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val first = Thread {
            firstResult.set(guard.runOnce {
                calls.incrementAndGet()
                entered.countDown()
                release.await()
            })
        }
        first.start()
        assertTrue("first cleanup should enter", entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val second = Thread { secondResult.set(guard.runOnce { calls.incrementAndGet() }) }
        second.start()
        second.join(2000)
        assertFalse("contending caller should return without waiting for cleanup", second.isAlive)
        assertEquals(false, secondResult.get())
        release.countDown()
        first.join(2000)
        assertFalse("first cleanup should complete", first.isAlive)
        assertEquals(true, firstResult.get())
        assertEquals(1, calls.get())
        assertTrue(guard.isCompleted())
    }

    @Test fun cleanupFailureIsLoggedAndReportedAsUnsuccessful() {
        val diagnostics = RuntimeDiagnostics()
        val success = safeRuntimeCleanup("test-cleanup", diagnostics) { throw IllegalStateException("native teardown fault") }
        assertFalse(success)
        assertTrue(diagnostics.snapshot().any { it.component == "test-cleanup" && it.message.contains("native teardown fault") })
    }

    @Test fun retainedProcessHandleBlocksLaunchUntilWatchdogClearsIt() {
        assertFalse(runtimeSessionCleanupPending(null))
        assertTrue(runtimeSessionCleanupPending(Any()))
    }

    @Test fun unexpectedPreparationFailureIsConvertedIntoFailureResult() {
        val result = safeRuntimePreparation { throw IllegalStateException("simulated preparation crash") }
        assertTrue(result is LaunchPreparationResult.MissingPayload)
        assertTrue((result as LaunchPreparationResult.MissingPayload).blockers.single().contains("simulated preparation crash"))
    }

}

