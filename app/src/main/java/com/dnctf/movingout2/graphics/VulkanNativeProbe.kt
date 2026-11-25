package com.dnctf.movingout2.graphics

data class SwapchainProbeResult(
    val status: String,
    val isReady: Boolean,
    val swapchainCreated: Boolean,
    val imageCount: Int,
    val width: Int,
    val height: Int,
    val format: Int,
    val acquirePassed: Boolean,
    val presentPassed: Boolean,
    val detail: String? = null
)

object VulkanNativeProbe {
    private val loaded: Boolean = runCatching { System.loadLibrary("vulkan_probe"); true }.getOrDefault(false)
    private external fun probeNative(): String
    private external fun probeSwapchainNative(surface: Any): String

    fun runProbe(): String? = if (loaded) runCatching { probeNative() }.getOrNull() else null

    fun probeSwapchain(surface: Any): SwapchainProbeResult {
        if (!loaded) {
            return SwapchainProbeResult(
                status = "library_not_loaded",
                isReady = false,
                swapchainCreated = false,
                imageCount = 0,
                width = 0,
                height = 0,
                format = 0,
                acquirePassed = false,
                presentPassed = false,
                detail = "Native vulkan_probe library is not loaded"
            )
        }
        val raw = runCatching { probeSwapchainNative(surface) }.getOrNull()
        return parseSwapchain(raw)
    }

    fun parse(raw: String?): Map<String, String> = raw.orEmpty().split(';')
        .mapNotNull { segment ->
            val index = segment.indexOf('=')
            if (index <= 0) null else segment.substring(0, index) to segment.substring(index + 1)
        }.toMap()

    fun parseSwapchain(raw: String?): SwapchainProbeResult {
        val map = parse(raw)
        val status = map["status"] ?: "unknown"
        val swapchainCreated = map["swapchain"] == "created"
        val acquire = map["acquire"] == "success"
        val present = map["present"] == "success"
        val ready = status == "ok" && swapchainCreated && acquire && present

        return SwapchainProbeResult(
            status = status,
            isReady = ready,
            swapchainCreated = swapchainCreated,
            imageCount = map["images"]?.toIntOrNull() ?: 0,
            width = map["width"]?.toIntOrNull() ?: 0,
            height = map["height"]?.toIntOrNull() ?: 0,
            format = map["format"]?.toIntOrNull() ?: 0,
            acquirePassed = acquire,
            presentPassed = present,
            detail = map["detail"] ?: if (!ready) "Vulkan presentation test unconfirmed" else null
        )
    }
}
