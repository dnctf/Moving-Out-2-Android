package com.dnctf.movingout2.compatibility

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

data class DeviceDiagnostics(
    val manufacturer: String, val model: String, val soc: String, val abi: List<String>, val ramBytes: Long?,
    val gpuVendorHint: String, val blockers: List<String>, val availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    val availableRamBytes: Long? = null, val lowMemory: Boolean = false, val vulkanFeatures: List<String> = emptyList()
)

object DeviceDiagnosticsProvider {
    fun collect(context: Context): DeviceDiagnostics {
        val parts = mutableListOf(Build.HARDWARE.orEmpty())
        if (Build.VERSION.SDK_INT >= 31) { parts += Build.SOC_MODEL.orEmpty(); parts += Build.SOC_MANUFACTURER.orEmpty() }
        val cpu = runCatching { File("/proc/cpuinfo").readText().take(8192) }.getOrNull().orEmpty()
        val fallback = Regex("(?im)^Hardware\\s*:\\s*(.+)$").find(cpu)?.groupValues?.getOrNull(1).orEmpty()
        val soc = (parts + fallback).filter(String::isNotBlank).distinct().joinToString(" / ").ifBlank { "Unknown SoC" }
        val hint = when {
            soc.contains("qualcomm", true) || soc.contains("snapdragon", true) -> "QUALCOMM_ADRENO_POTENTIAL"
            soc.contains("mali", true) || soc.contains("arm", true) || soc.contains("mediatek", true) -> "ARM_MALI_POTENTIAL"
            else -> "GPU_UNDETERMINED"
        }
        val mem = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.getMemoryInfo(mem)
        val vulkan = listOf(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            .filter(context.packageManager::hasSystemFeature)
        val blockers = buildList {
            if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) add("ARM64 ABI arm64-v8a not present.")
            add("GPU renderer/driver not conclusively probed; SoC-derived GPU label is only a heuristic.")
        }
        return DeviceDiagnostics(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), soc, Build.SUPPORTED_ABIS.toList(),
            mem.totalMem.takeIf { it > 0 }, hint, blockers, Runtime.getRuntime().availableProcessors(),
            mem.availMem.takeIf { it > 0 }, mem.lowMemory, vulkan)
    }
}
