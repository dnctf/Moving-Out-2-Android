package com.dnctf.movingout2.runtime

import java.io.File

class RuntimeManager(
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
    private val bundleRoot: File? = null,
    private val bundleValidator: RuntimeBundleValidator = RuntimeBundleValidator()
) {
    val state: RuntimeState = RuntimeState.NOT_INSTALLED
    fun diagnostics(): RuntimeDiagnostics = diagnostics
    fun bundleReport(): RuntimeBundleReport? = bundleRoot?.let(bundleValidator::validate)
    fun availability(): String {
        val report = bundleReport()
        return if (report?.isReady == true) "BUNDLE_VALIDATION_ONLY" else "MISSING_NATIVE_COMPONENTS"
    }
    fun start(): Nothing {
        val report = bundleReport()
        if (report?.isReady == true) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "runtime",
                "Runtime bundle structure validated, but Wine/Box64 launch integration is not implemented; launch remains blocked."))
        } else {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime",
                "Launch blocked: Wine/Box64 native runtime bundle is missing or invalid."))
        }
        throw UnsupportedOperationException("RUNTIME_NOT_INSTALLED: Wine/Box64 launch integration is not implemented.")
    }
}
