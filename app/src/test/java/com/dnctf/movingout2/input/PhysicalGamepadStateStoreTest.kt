package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class PhysicalGamepadStateStoreTest {
    @Test fun disconnectRemovesDeviceButtonsAndAnalogState() {
        val store = PhysicalGamepadStateStore()
        store.register(10); store.register(11)
        store.update(10, ControllerState(buttons=setOf(ControllerButton.A), leftX=.7f, leftTrigger=.8f))
        store.update(11, ControllerState(buttons=setOf(ControllerButton.B), rightY=-.5f))
        val before = store.aggregate()
        assertTrue(ControllerButton.A in before.buttons); assertTrue(ControllerButton.B in before.buttons)
        store.remove(10)
        val after = store.aggregate()
        assertFalse(ControllerButton.A in after.buttons); assertTrue(ControllerButton.B in after.buttons)
        assertEquals(0f, after.leftX, 0f); assertEquals(0f, after.leftTrigger, 0f)
        assertEquals(-.5f, after.rightY, 0f)
        store.remove(11)
        assertEquals(ControllerState(), store.aggregate())
    }
    @Test fun changedDeviceStateCanBeResetWithoutRemovingDevice() {
        val store = PhysicalGamepadStateStore()
        store.register(3); store.update(3, ControllerState(buttons=setOf(ControllerButton.RB), leftY=1f))
        store.resetDevice(3)
        assertEquals(1, store.deviceCount())
        assertEquals(ControllerState(), store.aggregate())
    }
    @Test fun updatesForUnknownDevicesAreIgnored() {
        val store = PhysicalGamepadStateStore()
        store.update(7, ControllerState(buttons=setOf(ControllerButton.X)))
        assertEquals(0, store.deviceCount())
        assertEquals(ControllerState(), store.aggregate())
    }
}
