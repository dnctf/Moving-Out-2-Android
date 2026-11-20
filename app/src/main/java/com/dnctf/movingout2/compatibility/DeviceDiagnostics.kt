package com.dnctf.movingout2.compatibility

import android.os.Build

data class DeviceDiagnostics(val manufacturer: String, val model: String, val soc: String, val abi: List<String>, val ramBytes: Long?, val gpuVendorHint: String, val blockers: List<String>)

object DeviceDiagnosticsProvider {
    fun collect(totalRamBytes: Long? = null): DeviceDiagnostics {
        val soc = listOf(Build.HARDWARE, Build.SOC_MODEL, Build.SOC_MANUFACTURER).filter { it.isNotBlank() }.distinct().joinToString(" / ")
        val hint = when {
            soc.contains("qualcomm", true) || soc.contains("snapdragon", true) -> "QUALCOMM_ADRENO_POTENTIAL"
            soc.contains("mediatek", true) || soc.contains("mali", true) -> "ARM_MALI_POTENTIAL"
            else -> "GPU_UNDETERMINED"
        }
        return DeviceDiagnostics(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), soc, Build.SUPPORTED_ABIS.toList(), totalRamBytes, hint, listOf("GPU model and Vulkan extension support require runtime probing; Android build properties are only hints."))
    }
}