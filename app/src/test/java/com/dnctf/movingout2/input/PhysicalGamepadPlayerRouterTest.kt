package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class PhysicalGamepadPlayerRouterTest {
    @Test fun devicesReceiveStableDistinctSlotsAndReuseReleasedSlots() {
        val router = PhysicalGamepadPlayerRouter()
        assertEquals(0, router.connect(10))
        assertEquals(1, router.connect(11))
        assertEquals(2, router.connect(12))
        assertEquals(3, router.connect(13))
        assertEquals(1, router.connect(11))
        assertNull(router.connect(14))
        assertEquals(1, router.disconnect(11))
        assertEquals(1, router.connect(14))
        assertEquals(4, router.deviceCount())
    }

    @Test fun disconnectAndClearAreIdempotent() {
        val router = PhysicalGamepadPlayerRouter()
        assertNull(router.disconnect(99))
        assertEquals(0, router.connect(99))
        assertEquals(0, router.disconnect(99))
        assertNull(router.disconnect(99))
        router.connect(1)
        router.connect(2)
        router.clear()
        assertEquals(0, router.deviceCount())
        assertEquals(0, router.connect(3))
    }

    @Test fun invalidPlayerCountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PhysicalGamepadPlayerRouter(0) }
        assertThrows(IllegalArgumentException::class.java) { PhysicalGamepadPlayerRouter(5) }
    }
}
