package com.dnctf.movingout2.runtime

data class RuntimeDiagnostic(val severity: Severity, val component: String, val message: String, val timestampMillis: Long = System.currentTimeMillis()) {
    enum class Severity { INFO, WARNING, ERROR }
}
/** Thread-safe bounded log buffer for runtime stdout/stderr and diagnostic events. */
class RuntimeDiagnostics(private val capacity: Int = 2000) {
    init { require(capacity > 0) }
    private val entries = mutableListOf<RuntimeDiagnostic>()
    @Synchronized fun record(entry: RuntimeDiagnostic) {
        entries += entry
        if (entries.size > capacity) entries.removeAt(0)
    }
    @Synchronized fun snapshot(): List<RuntimeDiagnostic> = entries.toList()
    @Synchronized fun clear() = entries.clear()
}