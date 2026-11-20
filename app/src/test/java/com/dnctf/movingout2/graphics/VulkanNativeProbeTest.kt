package com.dnctf.movingout2.graphics

import org.junit.Assert.*
import org.junit.Test

class VulkanNativeProbeTest {
    @Test fun parsesDeviceAndExtensions() {
        val parsed = VulkanNativeProbe.parse("loaderApi=1.3;status=ok;device=Adreno 740;vendorId=20803;deviceApi=1.3;deviceExtensions=VK_KHR_swapchain,VK_EXT_memory_budget")
        assertEquals("ok", parsed["status"])
        assertEquals("Adreno 740", parsed["device"])
        assertEquals("20803", parsed["vendorId"])
        assertEquals("VK_KHR_swapchain,VK_EXT_memory_budget", parsed["deviceExtensions"])
    }
    @Test fun ignoresMalformedSegments() {
        val parsed = VulkanNativeProbe.parse("nonsense;status=failed;=invalid")
        assertEquals(mapOf("status" to "failed"), parsed)
    }
}
