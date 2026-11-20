package com.dnctf.movingout2.runtime

class RuntimeManager(private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics()) {
    val state: RuntimeState = RuntimeState.NOT_INSTALLED
    fun diagnostics(): RuntimeDiagnostics = diagnostics
    fun availability(): String = "MISSING_NATIVE_COMPONENTS"
    fun start(): Nothing {
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime", "Launch blocked: Wine/Box64 native runtime is not integrated."))
        throw UnsupportedOperationException("RUNTIME_NOT_INSTALLED: native Wine/Box64 components are absent.")
    }
}