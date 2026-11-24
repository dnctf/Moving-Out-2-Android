package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class VirtualControllerEngineTest {
    @Test fun nonFiniteDeadzoneFallsBackToSafeDefault() {
        val result = VirtualControllerEngine.radialDeadzone(.5f, 0f, Float.NaN)
        assertTrue(result.x.isFinite())
        assertEquals(VirtualControllerEngine.radialDeadzone(.5f, 0f, .12f).x, result.x, 0f)
    }

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
        engine.claimTrigger(3, right = false)
        engine.setTrigger(3, 0.9f, right = false)
        val cleared = engine.releaseAll()
        assertTrue(cleared.buttons.isEmpty())
        assertEquals(0f, cleared.leftX, 0f)
        assertEquals(0f, cleared.leftY, 0f)
        assertEquals(0f, cleared.leftTrigger, 0f)
    }
    @Test fun triggersArePointerOwnedAndReleaseIndependently() {
        val engine = VirtualControllerEngine()
        assertTrue(engine.claimTrigger(10, false))
        assertTrue(engine.claimTrigger(11, true))
        assertTrue(engine.setTrigger(10, .75f, false))
        assertTrue(engine.setTrigger(11, .4f, true))
        engine.releasePointer(10)
        assertEquals(0f, engine.snapshot().leftTrigger, 0f)
        assertEquals(.4f, engine.snapshot().rightTrigger, .0001f)
    }
    @Test fun onePointerCannotClaimMultipleControls() {
        val engine = VirtualControllerEngine()
        assertTrue(engine.claimButton(1, ControllerButton.A))
        assertFalse(engine.claimStick(1))
        assertFalse(engine.claimTrigger(1, false))
    }
    @Test fun duplicateButtonPointerClaimsAreRejected() {
        val engine = VirtualControllerEngine()
        assertTrue(engine.claimButton(1, ControllerButton.X))
        assertFalse(engine.claimButton(2, ControllerButton.X))
        engine.releasePointer(1)
        assertTrue(engine.claimButton(2, ControllerButton.X))
    }

    @Test fun dpadPointerSupportsDiagonalAndSlidesBetweenDirections() {
        val engine = VirtualControllerEngine()
        assertTrue(engine.claimDpad(21, setOf(ControllerButton.UP, ControllerButton.RIGHT)))
        assertEquals(setOf(ControllerButton.UP, ControllerButton.RIGHT), engine.snapshot().buttons)
        assertTrue(engine.updateDpad(21, setOf(ControllerButton.DOWN)))
        assertEquals(setOf(ControllerButton.DOWN), engine.snapshot().buttons)
        engine.releasePointer(21)
        assertTrue(engine.snapshot().buttons.isEmpty())
    }

    @Test fun dpadPointerOwnsOneControlGroupAndReleasesOnCancel() {
        val engine = VirtualControllerEngine()
        assertTrue(engine.claimDpad(31, setOf(ControllerButton.LEFT, ControllerButton.UP)))
        assertFalse(engine.claimDpad(32, setOf(ControllerButton.RIGHT)))
        assertFalse(engine.claimStick(31))
        assertTrue(engine.releaseAll().buttons.isEmpty())
        assertFalse(engine.ownsDpad(31))
    }
}
