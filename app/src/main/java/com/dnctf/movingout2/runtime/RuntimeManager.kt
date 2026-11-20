package com.dnctf.movingout2.runtime

import java.io.File

class RuntimeManager(
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
    private val bundleRoot: File? = null,
    private val bundleValidator: RuntimeBundleValidator = RuntimeBundleValidator(),
    private val wineLauncher: WineRuntimeLauncher? = null
) {
    @Volatile var state: RuntimeState = RuntimeState.NOT_INSTALLED
        private set
    @Volatile private var activeProcess: ProcessResult? = null

    fun diagnostics(): RuntimeDiagnostics = diagnostics
    fun bundleReport(): RuntimeBundleReport? = bundleRoot?.let(bundleValidator::validate)
    fun availability(): String {
        val report = bundleReport()
        return if (report?.isReady == true && wineLauncher != null) "LAUNCH_PREPARATION_AVAILABLE" else "MISSING_NATIVE_COMPONENTS"
    }

    @Synchronized fun start(gameExe: File, options: WineLaunchOptions = WineLaunchOptions()): LaunchPreparationResult {
        if (activeProcess?.process?.isAlive == true) return LaunchPreparationResult.MissingPayload(listOf("A runtime process is already active."))
        val launcher = wineLauncher ?: return LaunchPreparationResult.MissingPayload(listOf("WineRuntimeLauncher is not configured."))
        state = RuntimeState.STARTING
        when (val prepared = launcher.prepare(gameExe, options)) {
            is LaunchPreparationResult.MissingPayload -> {
                state = RuntimeState.FAILED
                prepared.blockers.forEach { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", it)) }
                return prepared
            }
            is LaunchPreparationResult.Prepared -> {
                return try {
                    val running = launcher.launch(prepared.plan)
                    activeProcess = running
                    state = RuntimeState.RUNNING
                    Thread({
                        val result = runCatching { running.waitFor() }.getOrNull()
                        synchronized(this) {
                            activeProcess = null
                            state = if (result?.exitCode == 0) RuntimeState.STOPPED else RuntimeState.FAILED
                            launcher.stop(running, 0)
                            diagnostics.record(RuntimeDiagnostic(if (result?.exitCode == 0) RuntimeDiagnostic.Severity.INFO else RuntimeDiagnostic.Severity.WARNING, "runtime", "Game process exited with code ${result?.exitCode ?: "unknown"}"))
                        }
                    }, "mo2-runtime-watchdog").apply { isDaemon = true; start() }
                    prepared
                } catch (error: Exception) {
                    activeProcess = null
                    state = RuntimeState.FAILED
                    diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", "Launch failed: ${error.message}"))
                    LaunchPreparationResult.MissingPayload(listOf("Process launch failed: ${error.message}"))
                }
            }
        }
    }

    @Synchronized fun stop(): Boolean {
        val process = activeProcess ?: run { state = RuntimeState.STOPPED; return false }
        state = RuntimeState.STOPPING
        val launcher = wineLauncher
        runCatching { process.stop(1000) }
        runCatching { launcher?.neutralizeInputBridge() }
        runCatching { launcher?.stopWineserver() }
        activeProcess = null
        state = RuntimeState.STOPPED
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "runtime", "Runtime stopped by user."))
        return true
    }

    /** Kept as a fail-closed entry point when callers haven't supplied a game executable. */
    fun start(): Nothing {
        val report = bundleReport()
        if (report?.isReady == true && wineLauncher != null) diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "runtime", "Bundle validates; game executable is required to start."))
        else diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", "Launch blocked: native runtime bundle is missing or launcher unavailable."))
        throw UnsupportedOperationException("RUNTIME_NOT_READY: game executable and configured WineRuntimeLauncher are required.")
    }
}
