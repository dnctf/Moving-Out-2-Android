package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class VirtualControllerEngineTest {
    @Test fun radialClampingAndDeadzoneAreStable() {
        assertEquals(0f, VirtualControllerEngine.radialDeadzone(0.05f, 0.05f).x, 0.0001f)
        val full = VirtualControllerEngine.radialDeadzone(2f, 0f)
        assertEquals(1f, full.x, 0.0001f)
        assertEquals(0f, full.y, 0.0001f)
        val diagonal = VirtualControllerEngine.radialDeadzone(1f, 1f)
        assertTrue(diagonal.x <= 1f && diagonal.y <= 1f)
    }
    @Test fun pointerUpReleasesOnlyItsButtonAndStick() {
        val engine = VirtualControllerEngine()
        assertTrue(engine.claimButton(4, ControllerButton.A))
        assertTrue(engine.claimStick(8))
        engine.moveStick(8, 100f, 0f, 100f)
        engine.releasePointer(4)
        assertFalse(engine.snapshot().buttons.contains(ControllerButton.A))
        assertEquals(1f, engine.snapshot().leftX, 0.0001f)
        engine.releasePointer(8)
        assertEquals(0f, engine.snapshot().leftX, 0.0001f)
    }
    @Test fun cancelClearsAllState() {
        val engine = VirtualControllerEngine()
        engine.claimButton(1, ControllerButton.B)
        engine.claimStick(2)
        engine.moveStick(2, 40f, -40f, 40f)
        engine.setTrigger(2, 0.9f, right = false)
        val cleared = engine.releaseAll()
        assertTrue(cleared.buttons.isEmpty())
        assertEquals(0f, cleared.leftX, 0f)
        assertEquals(0f, cleared.leftY, 0f)
        assertEquals(0f, cleared.leftTrigger, 0f)
    }
    @Test fun duplicateButtonPointerClaimsAreRejected() {
        val engine = VirtualControllerEngine()
        assertTrue(engine.claimButton(1, ControllerButton.X))
        assertFalse(engine.claimButton(2, ControllerButton.X))
        engine.releasePointer(1)
        assertTrue(engine.claimButton(2, ControllerButton.X))
    }
}
