package com.dnctf.movingout2.runtime

data class RuntimeDiagnostic(val severity: Severity, val component: String, val message: String, val timestampMillis: Long = System.currentTimeMillis()) {
    enum class Severity { INFO, WARNING, ERROR }
}
class RuntimeDiagnostics {
    private val entries = mutableListOf<RuntimeDiagnostic>()
    @Synchronized fun record(entry: RuntimeDiagnostic) { entries += entry }
    @Synchronized fun snapshot(): List<RuntimeDiagnostic> = entries.toList()
    @Synchronized fun clear() = entries.clear()
}