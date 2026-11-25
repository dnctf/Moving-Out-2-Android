package com.dnctf.movingout2.runtime

/**
 * One readiness contract shared by launcher UI and exported diagnostics.
 * Structural bundle validity alone is not enough: Box64 must execute from the
 * APK-extracted native library directory and a validated game install must exist.
 */
data class RuntimeInstallationState(
    val runtimeBundleReady: Boolean,
    val execProbeAllowed: Boolean,
    val gameFilesPresent: Boolean
) {
    val canLaunch: Boolean
        get() = runtimeBundleReady && execProbeAllowed && gameFilesPresent

    val launchReadiness: String
        get() = when {
            !runtimeBundleReady -> "BLOCKED_RUNTIME_NOT_READY"
            !execProbeAllowed -> "BLOCKED_NATIVE_EXEC_PROBE"
            !gameFilesPresent -> "BLOCKED_GAME_FILES_MISSING"
            else -> "READY"
        }
}
