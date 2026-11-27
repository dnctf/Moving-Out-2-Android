package com.dnctf.movingout2.runtime

enum class GameLaunchSurfaceState { IDLE, WAITING_FOR_SURFACE, SURFACE_READY, RUNNING, STOPPING }

data class SurfaceLaunchDecision(
    val launchAllowed: Boolean,
    val stopSession: Boolean,
    val state: GameLaunchSurfaceState
)

/** Serializes launch intent with SurfaceView availability and active-session teardown. */
class GameLaunchSurfaceCoordinator {
    private var state = GameLaunchSurfaceState.IDLE

    @Synchronized
    fun requestLaunch(): SurfaceLaunchDecision {
        if (state == GameLaunchSurfaceState.RUNNING) {
            state = GameLaunchSurfaceState.STOPPING
            return SurfaceLaunchDecision(false, true, state)
        }
        state = GameLaunchSurfaceState.WAITING_FOR_SURFACE
        return SurfaceLaunchDecision(false, false, state)
    }

    @Synchronized
    fun onSurfaceReady(width: Int, height: Int): SurfaceLaunchDecision {
        if (width <= 0 || height <= 0 || state != GameLaunchSurfaceState.WAITING_FOR_SURFACE) {
            return SurfaceLaunchDecision(false, false, state)
        }
        state = GameLaunchSurfaceState.SURFACE_READY
        return SurfaceLaunchDecision(true, false, state)
    }

    @Synchronized
    fun onSessionStarted(): Boolean {
        if (state != GameLaunchSurfaceState.SURFACE_READY) return false
        state = GameLaunchSurfaceState.RUNNING
        return true
    }

    @Synchronized
    fun onSurfaceDestroyed(): SurfaceLaunchDecision {
        val shouldStop = state == GameLaunchSurfaceState.RUNNING ||
            state == GameLaunchSurfaceState.SURFACE_READY ||
            state == GameLaunchSurfaceState.WAITING_FOR_SURFACE
        state = when (state) {
            GameLaunchSurfaceState.RUNNING, GameLaunchSurfaceState.SURFACE_READY -> GameLaunchSurfaceState.STOPPING
            else -> GameLaunchSurfaceState.IDLE
        }
        return SurfaceLaunchDecision(false, shouldStop, state)
    }

    @Synchronized
    fun onSessionStopped() {
        state = GameLaunchSurfaceState.IDLE
    }

    @Synchronized
    fun currentState(): GameLaunchSurfaceState = state
}
