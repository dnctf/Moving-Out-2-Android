package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test fun bufferEvictsOldestEntriesAndPreservesOrder() {
        val diagnostics = RuntimeDiagnostics(capacity = 3)
        (1..4).forEach { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "test", "entry-$it")) }
        assertEquals(listOf("entry-2", "entry-3", "entry-4"), diagnostics.snapshot().map { it.message })
    }

    @Test fun circularBufferPreservesOrderAcrossMultipleWraps() {
        val diagnostics = RuntimeDiagnostics(capacity = 2)
        (1..7).forEach { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "test", "entry-$it")) }
        assertEquals(listOf("entry-6", "entry-7"), diagnostics.snapshot().map { it.message })
    }

    @Test fun capacityOneAlwaysKeepsMostRecentEntry() {
        val diagnostics = RuntimeDiagnostics(capacity = 1)
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "test", "old"))
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "test", "new"))
        assertEquals(listOf("new"), diagnostics.snapshot().map { it.message })
    }

    @Test fun snapshotIsIndependentAndClearResetsBuffer() {
        val diagnostics = RuntimeDiagnostics(capacity = 2)
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "test", "kept"))
        val snapshot = diagnostics.snapshot()
        diagnostics.clear()
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "test", "fresh"))
        assertEquals(listOf("kept"), snapshot.map { it.message })
        assertEquals(listOf("fresh"), diagnostics.snapshot().map { it.message })
    }

    @Test fun concurrentRecordsRemainBoundedAndAccessible() {
        val diagnostics = RuntimeDiagnostics(capacity = 100)
        val writers = (0 until 4).map { worker ->
            Thread { repeat(250) { index ->
                diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "worker-$worker", "item-$index"))
            } }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join(3000) }
        assertTrue("all writers should finish", writers.none { it.isAlive })
        val snapshot = diagnostics.snapshot()
        assertEquals(100, snapshot.size)
        assertTrue(snapshot.all { it.component.startsWith("worker-") })
    }
}
