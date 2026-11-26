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


class RuntimeRenderReadinessSnapshotTest {
    @org.junit.Test
    fun vulkanEnumerationAloneNeverMeansRenderingReady() {
        val snapshot = RuntimeRenderReadinessSnapshot(vulkanPhysicalDeviceEnumerated = true)
        org.junit.Assert.assertFalse(snapshot.isRenderingReady)
        org.junit.Assert.assertTrue(snapshot.blockers.contains("DXVK_D3D11_DEVICE_NOT_CREATED"))
        org.junit.Assert.assertTrue(snapshot.blockers.contains("SURFACE_FRAME_NOT_PRESENTED"))
    }

    @org.junit.Test
    fun renderingRequiresWineDxvkAndPresentedSurfaceFrame() {
        val snapshot = RuntimeRenderReadinessSnapshot(
            vulkanPhysicalDeviceEnumerated = false,
            winebootExitCode = 0,
            wineRegistryFilesReady = true,
            wineserverSocketReady = true,
            dxvkD3d11DeviceCreated = true,
            surfaceFramePresented = true
        )
        org.junit.Assert.assertTrue(snapshot.isRenderingReady)
        org.junit.Assert.assertTrue(snapshot.blockers.isEmpty())
    }

    @org.junit.Test
    fun failedWinebootBlocksRenderingEvenIfGraphicsSignalsArePresent() {
        val snapshot = RuntimeRenderReadinessSnapshot(
            vulkanPhysicalDeviceEnumerated = true,
            winebootExitCode = 1,
            wineRegistryFilesReady = true,
            wineserverSocketReady = true,
            dxvkD3d11DeviceCreated = true,
            surfaceFramePresented = true
        )
        org.junit.Assert.assertFalse(snapshot.isRenderingReady)
        org.junit.Assert.assertTrue(snapshot.blockers.contains("WINEBOOT_NOT_CONFIRMED"))
    }
}
