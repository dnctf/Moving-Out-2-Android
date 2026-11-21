package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RuntimeLifecycleCleanupTest {
    @Test fun cleanupGateRunsOnceForCrashAndWatchdogRace() {
        val guard = RuntimeCleanupGuard()
        val cleanupCalls = AtomicInteger()
        val latch = CountDownLatch(2)
        val callbacks = listOf("crash-exit", "watchdog-timeout").map {
            Thread {
                guard.runOnce { cleanupCalls.incrementAndGet() }
                latch.countDown()
            }.apply { start() }
        }
        latch.await()
        callbacks.forEach { it.join() }
        assertEquals(1, cleanupCalls.get())
        assertTrue(guard.isCompleted())
        assertFalse(guard.runOnce { cleanupCalls.incrementAndGet() })
        assertEquals(1, cleanupCalls.get())
    }

    @Test fun manualStopAndLateExitCallbackDoNotRepeatCleanup() {
        val guard = RuntimeCleanupGuard()
        var neutralizeDestroyAndWineserver = 0
        assertTrue(guard.runOnce { neutralizeDestroyAndWineserver++ })
        assertFalse(guard.runOnce { neutralizeDestroyAndWineserver++ })
        assertEquals(1, neutralizeDestroyAndWineserver)
    }
    @Test fun staleWatchdogCannotClaimCurrentRuntimeSession() {
        val previousProcess = Any()
        val currentProcess = Any()
        val previousGuard = RuntimeCleanupGuard()
        val currentGuard = RuntimeCleanupGuard()
        assertFalse(isRuntimeSessionCurrent(currentProcess, currentGuard, previousProcess, previousGuard))
        assertFalse(isRuntimeSessionCurrent(currentProcess, currentGuard, currentProcess, previousGuard))
        assertTrue(isRuntimeSessionCurrent(currentProcess, currentGuard, currentProcess, currentGuard))
    }
    @Test fun cleanupSequenceStopsProcessBeforeDestroyingSharedInputAndKeepsGoingOnErrors() {
        val events = mutableListOf<String>()
        runRuntimeCleanupSequence(
            neutralize = { events += "neutralize" },
            stopProcess = { events += "stop-process"; throw IllegalStateException("simulated stop failure") },
            destroyBridge = { events += "destroy-shm" },
            stopServer = { events += "stop-wineserver" }
        )
        assertEquals(listOf("neutralize", "stop-process", "destroy-shm", "stop-wineserver"), events)
    }

    @Test fun runtimeImporterReserveThresholdIsEnforced() {
        assertTrue(runtimeBundleHasReserve(512L * 1024L * 1024L, 512L * 1024L * 1024L))
        assertFalse(runtimeBundleHasReserve(512L * 1024L * 1024L - 1, 512L * 1024L * 1024L))
        assertFalse(runtimeBundleHasReserve(100, -1))
    }

}
