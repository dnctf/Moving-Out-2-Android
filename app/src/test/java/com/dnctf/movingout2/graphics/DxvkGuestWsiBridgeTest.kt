package com.dnctf.movingout2.graphics

import org.junit.Assert.*
import org.junit.Test

class DxvkGuestWsiBridgeTest {
    @Test fun launcherSurfaceProbeDoesNotPretendToBeGuestDxvkWsi() {
        assertFalse(DxvkGuestWsiBridge.isAvailable())
        assertFalse(DxvkGuestWsiBridge.canLaunchWineSession(surfaceReady = true))
        assertFalse(DxvkGuestWsiBridge.canLaunchWineSession(surfaceReady = false))
        assertEquals("DXVK_GUEST_WSI_BRIDGE_NOT_IMPLEMENTED", DxvkGuestWsiBridge.BLOCKER)
    }
}
