package com.dnctf.movingout2.runtime

/** Pure launch-readiness state machine; UI and background work feed one immutable snapshot. */
data class LaunchReadinessSnapshot(
    val gameImportedToAppStorage: Boolean,
    val runtimeBundleReady: Boolean,
    val execProbeAllowed: Boolean,
    val vulkanAvailable: Boolean,
    val refreshComplete: Boolean
) {
    val isAllReady: Boolean
        get() = gameImportedToAppStorage && runtimeBundleReady && execProbeAllowed &&
            vulkanAvailable && refreshComplete
}

data class LaunchReadinessDecision(
    val playEnabled: Boolean,
    val shouldAutoLaunch: Boolean,
    val readiness: String
)

class LaunchReadinessCoordinator(initiallyLaunched: Boolean = false) {
    private var hasAutoLaunchedInSession = initiallyLaunched

    @Synchronized
    fun evaluate(snapshot: LaunchReadinessSnapshot): LaunchReadinessDecision {
        if (!snapshot.refreshComplete) return LaunchReadinessDecision(false, false, "CHECKS_IN_PROGRESS")
        if (!snapshot.gameImportedToAppStorage) return LaunchReadinessDecision(false, false, "BLOCKED_GAME_IMPORT")
        if (!snapshot.runtimeBundleReady) return LaunchReadinessDecision(false, false, "BLOCKED_RUNTIME")
        if (!snapshot.execProbeAllowed) return LaunchReadinessDecision(false, false, "BLOCKED_NATIVE_EXEC_PROBE")
        if (!snapshot.vulkanAvailable) return LaunchReadinessDecision(false, false, "BLOCKED_VULKAN")
        val trigger = !hasAutoLaunchedInSession
        if (trigger) hasAutoLaunchedInSession = true
        return LaunchReadinessDecision(true, trigger, "READY")
    }

    @Synchronized
    fun markAutoLaunched() { hasAutoLaunchedInSession = true }

    @Synchronized
    fun hasAutoLaunched(): Boolean = hasAutoLaunchedInSession
}
