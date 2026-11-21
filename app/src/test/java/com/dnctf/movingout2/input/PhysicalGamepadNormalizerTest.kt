package com.dnctf.movingout2.input

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class PhysicalGamepadNormalizerTest {
    @Test fun stickUsesRadialDeadzoneAndClampsMagnitude() {
        assertEquals(0f, PhysicalGamepadNormalizer.normalizeStick(.05f, .05f).x, .00001f)
        assertEquals(1f, PhysicalGamepadNormalizer.normalizeStick(2f, 0f).x, .00001f)
        val diagonal = PhysicalGamepadNormalizer.normalizeStick(1f, 1f)
        assertEquals(0.7071067f, diagonal.x, .0001f)
        assertEquals(0.7071067f, diagonal.y, .0001f)
    }
    @Test fun triggerRangesAndNonFiniteSamplesAreSafe() {
        assertEquals(0f, PhysicalGamepadNormalizer.normalizeTrigger(-1f), 0f)
        assertEquals(.5f, PhysicalGamepadNormalizer.normalizeTrigger(0f), 0f)
        assertEquals(1f, PhysicalGamepadNormalizer.normalizeTrigger(1f), 0f)
        assertEquals(0f, PhysicalGamepadNormalizer.normalizeTrigger(Float.NaN), 0f)
        assertEquals(1f, PhysicalGamepadNormalizer.normalizeTrigger(2f), 0f)
    }
    @Test fun hatProducesIndependentDirections() {
        assertEquals(setOf(ControllerButton.LEFT, ControllerButton.UP), PhysicalGamepadNormalizer.hatButtons(-1f, -1f))
        assertTrue(PhysicalGamepadNormalizer.hatButtons(0f, 0f).isEmpty())
    }
    @Test fun standardAndroidButtonsMapToCanonicalButtons() {
        assertEquals(ControllerButton.A, GamepadKeyMapping.mapKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(ControllerButton.RB, GamepadKeyMapping.mapKeyCode(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(ControllerButton.UP, GamepadKeyMapping.mapKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(GamepadKeyMapping.mapKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }
    @Test fun resetClearsAllAxesAndButtons() {
        val n = PhysicalGamepadNormalizer()
        n.update(1f, 0f, 0f, -1f, 1f, .8f, setOf(ControllerButton.A))
        val s = n.reset()
        assertEquals(0f, s.lx, 0f); assertEquals(0f, s.ry, 0f)
        assertEquals(0f, s.lt, 0f); assertTrue(s.buttons.isEmpty())
    }
}
