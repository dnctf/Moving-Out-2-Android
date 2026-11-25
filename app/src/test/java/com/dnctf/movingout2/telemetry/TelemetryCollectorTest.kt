package com.dnctf.movingout2.telemetry

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TelemetryCollectorTest {

    @Test
    fun computesAccurateFpsAndFrameTime() {
        val collector = TelemetryCollector()

        // Simulate 61 frames at 33.33ms (30 FPS)
        var timeNanos = 1_000_000_000L
        val frameIntervalNanos = 33_333_333L

        for (i in 0..60) {
            collector.onFramePresented(timeNanos)
            timeNanos += frameIntervalNanos
        }

        val snapshot = collector.collect()
        assertTrue("FPS should be close to 30: ${snapshot.fps}", snapshot.fps in 29.5..30.5)
        assertTrue("Frame time should be close to 33.3ms: ${snapshot.frameTimeMs}", snapshot.frameTimeMs in 32.5..34.0)
    }

    @Test
    fun handlesZeroOrSingleFrameGracefully() {
        val collector = TelemetryCollector()
        val emptySnapshot = collector.collect()
        assertEquals(0.0, emptySnapshot.fps, 0.001)
        assertEquals(0.0, emptySnapshot.frameTimeMs, 0.001)

        collector.onFramePresented(1_000_000L)
        val singleFrameSnapshot = collector.collect()
        assertEquals(0.0, singleFrameSnapshot.fps, 0.001)
        assertEquals(0.0, singleFrameSnapshot.frameTimeMs, 0.001)
    }

    @Test
    fun parsesProcStatusMemoryAccurately() {
        val tempStatus = File.createTempFile("proc_status_test", ".txt")
        try {
            tempStatus.writeText(
                """
                Name:   com.dnctf.movingout2
                State:  R (running)
                VmSize:   512000 kB
                VmRSS:    153600 kB
                Threads: 8
                """.trimIndent()
            )

            val collector = TelemetryCollector()
            val (rssMb, vmSizeMb) = collector.readMemoryFromProcStatus(tempStatus)

            assertEquals(150.0, rssMb, 0.01)
            assertEquals(500.0, vmSizeMb, 0.01)
        } finally {
            tempStatus.delete()
        }
    }

    @Test
    fun formatsHudStringProperly() {
        val snapshot = TelemetrySnapshot(
            fps = 59.8,
            frameTimeMs = 16.7,
            ramRssMb = 240.5,
            ramVmSizeMb = 800.0,
            thermalStatus = "NONE",
            thermalStatusCode = 0
        )
        val hud = snapshot.toFormattedHud()
        assertTrue(hud.contains("FPS: 59.8"))
        assertTrue(hud.contains("16.7ms"))
        assertTrue(hud.contains("240.5 MB"))
        assertTrue(hud.contains("Thermals: NONE"))
    }
}
