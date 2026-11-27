package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test

class GameLaunchSurfaceCoordinatorTest {
    @Test fun launchWaitsForCreatedSurfaceWithPositiveDimensions() {
        val coordinator = GameLaunchSurfaceCoordinator()
        assertEquals(GameLaunchSurfaceState.WAITING_FOR_SURFACE, coordinator.requestLaunch().state)
        assertFalse(coordinator.onSurfaceReady(0, 720).launchAllowed)
        assertFalse(coordinator.onSurfaceReady(1280, 0).launchAllowed)
        val ready = coordinator.onSurfaceReady(1280, 720)
        assertTrue(ready.launchAllowed)
        assertEquals(GameLaunchSurfaceState.SURFACE_READY, ready.state)
        assertTrue(coordinator.onSessionStarted())
        assertEquals(GameLaunchSurfaceState.RUNNING, coordinator.currentState())
    }

    @Test fun destroyedSurfaceRequestsSessionStopAndResetsAfterCleanup() {
        val coordinator = GameLaunchSurfaceCoordinator()
        coordinator.requestLaunch()
        assertTrue(coordinator.onSurfaceReady(1920, 1080).launchAllowed)
        assertTrue(coordinator.onSessionStarted())
        val destroyed = coordinator.onSurfaceDestroyed()
        assertTrue(destroyed.stopSession)
        assertEquals(GameLaunchSurfaceState.STOPPING, destroyed.state)
        coordinator.onSessionStopped()
        assertEquals(GameLaunchSurfaceState.IDLE, coordinator.currentState())
    }

    @Test fun destroyedSurfaceCancelsPendingLaunchBeforeRuntimeStarts() {
        val coordinator = GameLaunchSurfaceCoordinator()
        coordinator.requestLaunch()
        val destroyed = coordinator.onSurfaceDestroyed()
        assertTrue(destroyed.stopSession)
        assertFalse(coordinator.onSurfaceReady(1280, 720).launchAllowed)
        assertEquals(GameLaunchSurfaceState.IDLE, coordinator.currentState())
    }
}
