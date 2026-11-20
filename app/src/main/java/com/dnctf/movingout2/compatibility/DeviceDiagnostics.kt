package com.dnctf.movingout2.compatibility

import android.os.Build
import android.os.SystemClock

data class DeviceDiagnostics(val manufacturer: String, val model: String, val soc: String, val abi: List<String>, val ramBytes: Long?, val gpuVendorHint: String, val blockers: List<String>)

object DeviceDiagnosticsProvider {
    fun collect(totalRamBytes: Long? = null): DeviceDiagnostics {
        val socParts = mutableListOf(Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= 31) {
            socParts += Build.SOC_MODEL
            socParts += Build.SOC_MANUFACTURER
        }
        val soc = socParts.filter { it.isNotBlank() }.distinct().joinToString(" / ")
        val hint = when {
            soc.contains("qualcomm", true) || soc.contains("snapdragon", true) -> "QUALCOMM_ADRENO_POTENTIAL"
            soc.contains("mediatek", true) || soc.contains("mali", true) -> "ARM_MALI_POTENTIAL"
            else -> "GPU_UNDETERMINED"
        }
        return DeviceDiagnostics(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), soc, Build.SUPPORTED_ABIS.toList(), totalRamBytes, hint, listOf("GPU model and Vulkan extensions require native/runtime probing; SoC strings are hints only."))
    }
}