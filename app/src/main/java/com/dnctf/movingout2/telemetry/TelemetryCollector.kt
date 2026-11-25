package com.dnctf.movingout2.telemetry

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.ArrayDeque

data class TelemetrySnapshot(
    val fps: Double,
    val frameTimeMs: Double,
    val ramRssMb: Double,
    val ramVmSizeMb: Double,
    val thermalStatus: String,
    val thermalStatusCode: Int,
    val timestampEpochMs: Long = System.currentTimeMillis()
) {
    fun toFormattedHud(): String =
        "FPS: %.1f (%.1fms) | RAM: %.1f MB | Thermals: %s".format(
            fps, frameTimeMs, ramRssMb, thermalStatus
        )
}

class TelemetryCollector(private val context: Context? = null) {
    private val frameTimestampsNanos = ArrayDeque<Long>(64)
    private val maxFrameHistory = 60

    @Synchronized
    fun onFramePresented(timestampNanos: Long = System.nanoTime()) {
        frameTimestampsNanos.addLast(timestampNanos)
        while (frameTimestampsNanos.size > maxFrameHistory) {
            frameTimestampsNanos.removeFirst()
        }
    }

    @Synchronized
    fun collect(): TelemetrySnapshot {
        val (fps, frameTime) = computeFpsAndFrameTime()
        val (rssMb, vmSizeMb) = readMemoryFromProcStatus()
        val (thermalStr, thermalCode) = readThermalStatus()

        return TelemetrySnapshot(
            fps = fps,
            frameTimeMs = frameTime,
            ramRssMb = rssMb,
            ramVmSizeMb = vmSizeMb,
            thermalStatus = thermalStr,
            thermalStatusCode = thermalCode
        )
    }

    @Synchronized
    fun resetFrames() {
        frameTimestampsNanos.clear()
    }

    private fun computeFpsAndFrameTime(): Pair<Double, Double> {
        if (frameTimestampsNanos.size < 2) return Pair(0.0, 0.0)
        val first = frameTimestampsNanos.first()
        val last = frameTimestampsNanos.last()
        val totalDurationNanos = (last - first).coerceAtLeast(1L)
        val frames = frameTimestampsNanos.size - 1

        val fps = (frames.toDouble() * 1_000_000_000.0) / totalDurationNanos.toDouble()
        val frameTimeMs = (totalDurationNanos.toDouble() / frames.toDouble()) / 1_000_000.0
        return Pair(fps, frameTimeMs)
    }

    internal fun readMemoryFromProcStatus(statusFile: File = File("/proc/self/status")): Pair<Double, Double> {
        var rssKb = 0L
        var vmSizeKb = 0L

        if (statusFile.isFile && statusFile.canRead()) {
            runCatching {
                statusFile.forEachLine { line ->
                    if (line.startsWith("VmRSS:")) {
                        rssKb = parseKbValue(line)
                    } else if (line.startsWith("VmSize:")) {
                        vmSizeKb = parseKbValue(line)
                    }
                }
            }
        }

        if (rssKb == 0L) {
            // Fallback to JVM runtime memory if /proc is restricted by Android sandbox
            val runtime = Runtime.getRuntime()
            val usedBytes = runtime.totalMemory() - runtime.freeMemory()
            rssKb = usedBytes / 1024L
            vmSizeKb = runtime.totalMemory() / 1024L
        }

        return Pair(rssKb / 1024.0, vmSizeKb / 1024.0)
    }

    private fun parseKbValue(line: String): Long {
        return line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
    }

    private fun readThermalStatus(): Pair<String, Int> {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Pair("NONE", 0)
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return Pair("NONE", 0)

        val code = runCatching { pm.currentThermalStatus }.getOrDefault(0)
        val label = when (code) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($code)"
        }
        return Pair(label, code)
    }
}
