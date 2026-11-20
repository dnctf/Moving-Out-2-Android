package com.dnctf.movingout2.runtime

import java.io.File

/** Process wrapper; intentionally refuses to launch until native runtime binaries are supplied. */
class RuntimeProcess(private val diagnostics: RuntimeDiagnostics) {
    fun launch(executable: File, args: List<String> = emptyList(), environment: Map<String, String> = emptyMap()): Process {
        require(executable.exists() && executable.canExecute()) { "Runtime executable missing or not executable: $executable" }
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "process", "Native runtime launch is blocked: runtime integration is not implemented."))
        throw UnsupportedOperationException("MISSING_NATIVE_COMPONENTS: Wine/Box64 integration is not implemented.")
    }
}