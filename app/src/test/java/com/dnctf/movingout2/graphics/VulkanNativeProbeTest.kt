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

    @Test fun parsesSwapchainSuccessfulProbe() {
        val raw = "status=ok;swapchain=created;images=3;width=1920;height=1080;format=44;colorSpace=0;acquire=success;present=success"
        val result = VulkanNativeProbe.parseSwapchain(raw)
        assertEquals("ok", result.status)
        assertTrue(result.isReady)
        assertTrue(result.swapchainCreated)
        assertEquals(3, result.imageCount)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertEquals(44, result.format)
        assertTrue(result.acquirePassed)
        assertTrue(result.presentPassed)
        assertNull(result.detail)
    }

    @Test fun parsesSwapchainFailedAcquireOrPresent() {
        val raw = "status=ok;swapchain=created;images=2;width=1280;height=720;format=44;colorSpace=0;acquire=success;present=failed"
        val result = VulkanNativeProbe.parseSwapchain(raw)
        assertEquals("ok", result.status)
        assertFalse(result.isReady)
        assertTrue(result.swapchainCreated)
        assertTrue(result.acquirePassed)
        assertFalse(result.presentPassed)
        assertNotNull(result.detail)
    }

    @Test fun parsesSwapchainErrorState() {
        val raw = "status=error;detail=missing_instance_extensions;surface=1;android_surface=0"
        val result = VulkanNativeProbe.parseSwapchain(raw)
        assertEquals("error", result.status)
        assertFalse(result.isReady)
        assertFalse(result.swapchainCreated)
        assertEquals("missing_instance_extensions", result.detail)
    }

    @Test fun handlesNullRawSwapchainGracefully() {
        val result = VulkanNativeProbe.parseSwapchain(null)
        assertEquals("unknown", result.status)
        assertFalse(result.isReady)
        assertFalse(result.swapchainCreated)
        assertEquals(0, result.imageCount)
    }
}
