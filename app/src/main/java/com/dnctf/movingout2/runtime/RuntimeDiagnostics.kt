package com.dnctf.movingout2.runtime

data class RuntimeDiagnostic(val severity: Severity, val component: String, val message: String, val timestampMillis: Long = System.currentTimeMillis()) {
    enum class Severity { INFO, WARNING, ERROR }
}
/** Thread-safe bounded log buffer for runtime stdout/stderr and diagnostic events. */
class RuntimeDiagnostics(private val capacity: Int = 2000) {
    init { require(capacity > 0) }
    private val entries = arrayOfNulls<RuntimeDiagnostic>(capacity)
    private var nextIndex = 0
    private var size = 0

    /** Appends in O(1), overwriting the oldest entry when the buffer is full. */
    @Synchronized fun record(entry: RuntimeDiagnostic) {
        entries[nextIndex] = entry
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++
    }

    /** Returns an immutable chronological snapshot, oldest entry first. */
    @Synchronized fun snapshot(): List<RuntimeDiagnostic> {
        if (size == 0) return emptyList()
        val firstIndex = if (size == capacity) nextIndex else 0
        return List(size) { offset -> entries[(firstIndex + offset) % capacity]!! }
    }

    @Synchronized fun clear() {
        entries.fill(null)
        nextIndex = 0
        size = 0
    }
}
