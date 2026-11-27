package com.dnctf.movingout2.graphics

/**
 * Explicit capability boundary between the Android launcher process and the Box64 guest.
 *
 * VulkanNativeProbe can acquire/present a diagnostic swapchain in the launcher process,
 * but the current runtime has no cross-process Android WSI transport for the ANativeWindow.
 * Do not treat the diagnostic probe as a DXVK surface or launch the game into a black screen.
 */
object DxvkGuestWsiBridge {
    const val BLOCKER = "DXVK_GUEST_WSI_BRIDGE_NOT_IMPLEMENTED"

    fun isAvailable(): Boolean = false
}
