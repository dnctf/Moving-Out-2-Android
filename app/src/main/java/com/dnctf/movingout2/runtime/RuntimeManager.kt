package com.dnctf.movingout2.runtime

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates a launch only when the manager's bundle and the launcher's bundle
 * are the same canonical directory and the manager's validation is READY.
 */
class RuntimeManager(
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
    private val bundleRoot: File? = null,
    private val bundleValidator: RuntimeBundleValidator = RuntimeBundleValidator(),
    private val wineLauncher: WineRuntimeLauncher? = null
) {
    @Volatile var state: RuntimeState = RuntimeState.NOT_INSTALLED
        private set
    @Volatile private var activeProcess: ProcessResult? = null
    @Volatile private var activeCleanupGuard: RuntimeCleanupGuard? = null

    fun diagnostics(): RuntimeDiagnostics = diagnostics
    fun bundleReport(): RuntimeBundleReport? = bundleRoot?.let(bundleValidator::validate)

    fun availability(): String {
        val report = bundleReport()
        return when {
            report?.isReady != true -> "MISSING_NATIVE_COMPONENTS"
            wineLauncher == null -> "BUNDLE_VALIDATION_ONLY"
            !runtimeBundleRootsMatch(bundleRoot, wineLauncher.bundleRoot) -> "BUNDLE_CONFIGURATION_MISMATCH"
            else -> "LAUNCH_PREPARATION_AVAILABLE"
        }
    }

    @Synchronized fun start(gameExe: File, options: WineLaunchOptions = WineLaunchOptions()): LaunchPreparationResult {
        if (activeProcess?.process?.isAlive == true) {
            return LaunchPreparationResult.MissingPayload(listOf("A runtime process is already active."))
        }
        if (state == RuntimeState.STARTING || state == RuntimeState.STOPPING) {
            return LaunchPreparationResult.MissingPayload(listOf("Runtime state $state does not permit a new launch."))
        }
        if (state == RuntimeState.RUNNING) {
            activeProcess = null
            state = RuntimeStateTransitions.transition(state, RuntimeState.FAILED)
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "runtime", "State was RUNNING but no live process remained; marked failed before retry."))
        }

        val report = bundleReport()
        if (report?.isReady != true) {
            val blockers = report?.blockers ?: listOf("Runtime bundle root is not configured.")
            resetToNotInstalledIfSafe()
            blockers.forEach { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", it)) }
            return LaunchPreparationResult.MissingPayload(blockers)
        }
        val launcher = wineLauncher ?: run {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", "Launch blocked: WineRuntimeLauncher is not configured."))
            return LaunchPreparationResult.MissingPayload(listOf("WineRuntimeLauncher is not configured."))
        }
        if (!runtimeBundleRootsMatch(bundleRoot, launcher.bundleRoot)) {
            val blocker = "RuntimeManager and WineRuntimeLauncher bundle roots do not match; launch blocked."
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", blocker))
            return LaunchPreparationResult.MissingPayload(listOf(blocker))
        }

        if (state == RuntimeState.NOT_INSTALLED) state = RuntimeStateTransitions.transition(state, RuntimeState.READY)
        state = RuntimeStateTransitions.transition(state, RuntimeState.STARTING)
        when (val prepared = launcher.prepare(gameExe, options)) {
            is LaunchPreparationResult.MissingPayload -> {
                state = RuntimeStateTransitions.transition(state, RuntimeState.FAILED)
                prepared.blockers.forEach { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", it)) }
                return prepared
            }
            is LaunchPreparationResult.Prepared -> {
                return try {
                    val running = launcher.launch(prepared.plan)
                    activeProcess = running
                    val cleanupGuard = RuntimeCleanupGuard()
                    activeCleanupGuard = cleanupGuard
                    state = RuntimeStateTransitions.transition(state, RuntimeState.RUNNING)
                    Thread({
                        val result = runCatching { running.waitFor() }.getOrNull()
                        synchronized(this) {
                            activeProcess = null
                            val next = when {
                                state == RuntimeState.STOPPING -> RuntimeState.STOPPED
                                state == RuntimeState.RUNNING && result?.exitCode == 0 -> RuntimeState.STOPPED
                                state == RuntimeState.RUNNING -> RuntimeState.FAILED
                                else -> state
                            }
                            if (next != state) state = RuntimeStateTransitions.transition(state, next)
                            cleanupGuard.runOnce { launcher.stop(running, 0) }
                            diagnostics.record(RuntimeDiagnostic(
                                if (state == RuntimeState.STOPPED) RuntimeDiagnostic.Severity.INFO else RuntimeDiagnostic.Severity.WARNING,
                                "runtime",
                                "Game process exited with code ${result?.exitCode ?: "unknown"}; final state=$state"
                            ))
                        }
                    }, "mo2-runtime-watchdog").apply { isDaemon = true; start() }
                    prepared
                } catch (error: Exception) {
                    activeProcess = null
                    activeCleanupGuard = null
                    state = RuntimeStateTransitions.transition(state, RuntimeState.FAILED)
                    diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", "Launch failed: ${error.message}"))
                    LaunchPreparationResult.MissingPayload(listOf("Process launch failed: ${error.message}"))
                }
            }
        }
    }

    private fun resetToNotInstalledIfSafe() {
        if (state == RuntimeState.READY || state == RuntimeState.STOPPED || state == RuntimeState.FAILED) {
            state = RuntimeStateTransitions.transition(state, RuntimeState.NOT_INSTALLED)
        }
    }

    @Synchronized fun stop(): Boolean {
        val process = activeProcess ?: return false
        if (state == RuntimeState.RUNNING) state = RuntimeStateTransitions.transition(state, RuntimeState.STOPPING)
        else if (state != RuntimeState.STOPPING) return false
        val launcher = wineLauncher
        val guard = activeCleanupGuard ?: RuntimeCleanupGuard()
        if (!guard.runOnce { runCatching { launcher?.stop(process, 1000) }.getOrThrow() }) runCatching { process.stop(1000) }
        activeProcess = null
        activeCleanupGuard = null
        if (state == RuntimeState.STOPPING) state = RuntimeStateTransitions.transition(state, RuntimeState.STOPPED)
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "runtime", "Runtime stopped by user."))
        return true
    }

    /** Fail-closed entry point when no game executable is supplied. */
    fun start(): Nothing {
        val report = bundleReport()
        val launcher = wineLauncher
        val message = when {
            report?.isReady != true -> "RUNTIME_NOT_INSTALLED: native runtime bundle is missing or invalid."
            launcher == null -> "RUNTIME_NOT_READY: WineRuntimeLauncher is not configured."
            !runtimeBundleRootsMatch(bundleRoot, launcher.bundleRoot) -> "RUNTIME_NOT_READY: manager and launcher bundle roots differ."
            else -> "RUNTIME_NOT_READY: game executable is required to start."
        }
        diagnostics.record(RuntimeDiagnostic(
            if (report?.isReady == true && launcher != null && runtimeBundleRootsMatch(bundleRoot, launcher.bundleRoot)) RuntimeDiagnostic.Severity.WARNING else RuntimeDiagnostic.Severity.ERROR,
            "runtime",
            message
        ))
        throw UnsupportedOperationException(message)
    }
}

/** Canonical path equality prevents diagnostics/availability from validating a different bundle than the launcher uses. */
internal fun runtimeBundleRootsMatch(managerRoot: File?, launcherRoot: File?): Boolean {
    if (managerRoot == null || launcherRoot == null) return false
    return runCatching { managerRoot.canonicalFile == launcherRoot.canonicalFile }.getOrDefault(false)
}

/** Per-session cleanup gate shared by manual stop and the process watchdog. */
internal class RuntimeCleanupGuard {
    private val completed = AtomicBoolean(false)
    fun runOnce(cleanup: () -> Unit): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        cleanup()
        return true
    }
    fun isCompleted() = completed.get()
}
