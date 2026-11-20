package com.dnctf.movingout2.graphics

object VulkanNativeProbe {
    private val loaded: Boolean = runCatching { System.loadLibrary("vulkan_probe"); true }.getOrDefault(false)
    private external fun probeNative(): String

    fun runProbe(): String? = if (loaded) runCatching { probeNative() }.getOrNull() else null
    fun parse(raw: String?): Map<String, String> = raw.orEmpty().split(';')
        .mapNotNull { segment ->
            val index = segment.indexOf('=')
            if (index <= 0) null else segment.substring(0, index) to segment.substring(index + 1)
        }.toMap()
}
