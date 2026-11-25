package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test

class LaunchReadinessCoordinatorTest {
    private val ready = LaunchReadinessSnapshot(
        gameImportedToAppStorage = true,
        runtimeBundleReady = true,
        execProbeAllowed = true,
        vulkanAvailable = true,
        refreshComplete = true
    )

    @Test fun waitsUntilEveryReadinessGateIsSatisfied() {
        val coordinator = LaunchReadinessCoordinator()
        val blocked = coordinator.evaluate(ready.copy(vulkanAvailable = false))
        assertFalse(blocked.playEnabled)
        assertFalse(blocked.shouldAutoLaunch)
        assertEquals("BLOCKED_VULKAN", blocked.readiness)

        val readyDecision = coordinator.evaluate(ready)
        assertTrue(readyDecision.playEnabled)
        assertTrue(readyDecision.shouldAutoLaunch)
        assertEquals("READY", readyDecision.readiness)
    }

    @Test fun automaticallyLaunchesOnlyOnceButKeepsManualPlayEnabled() {
        val coordinator = LaunchReadinessCoordinator()
        val first = coordinator.evaluate(ready)
        assertTrue(first.playEnabled)
        assertTrue(first.shouldAutoLaunch)

        val afterGameExit = coordinator.evaluate(ready)
        assertTrue(afterGameExit.playEnabled)
        assertFalse(afterGameExit.shouldAutoLaunch)
        assertTrue(coordinator.hasAutoLaunched())
    }

    @Test fun incompleteRefreshNeverTriggersAutomaticLaunch() {
        val coordinator = LaunchReadinessCoordinator()
        val pending = coordinator.evaluate(ready.copy(refreshComplete = false))
        assertFalse(pending.playEnabled)
        assertFalse(pending.shouldAutoLaunch)
        assertEquals("CHECKS_IN_PROGRESS", pending.readiness)

        val completed = coordinator.evaluate(ready)
        assertTrue(completed.shouldAutoLaunch)
    }

    @Test fun restoredSessionGuardPreventsSecondAutomaticLaunch() {
        val coordinator = LaunchReadinessCoordinator(initiallyLaunched = true)
        val decision = coordinator.evaluate(ready)
        assertTrue(decision.playEnabled)
        assertFalse(decision.shouldAutoLaunch)
    }
}
