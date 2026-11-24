package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class GamepadTranslatorTest {
    @Test fun motionCancelClearsButtonsHatSticksAndTriggers() {
        val translator = GamepadTranslator()
        translator.applyButton(ControllerButton.A, true)
        translator.applyButton(ControllerButton.UP, true)
        translator.updateNormalizedMotion(
            PhysicalGamepadNormalizer.Stick(.65f, -.2f),
            PhysicalGamepadNormalizer.Stick(-.4f, .8f),
            .75f,
            .35f,
            setOf(ControllerButton.RIGHT)
        )
        val active = translator.onMotionCancelled()
        assertEquals(ControllerState(), active)
        assertEquals(ControllerState(), translator.releaseAll())
    }

    @Test fun keyDpadAndHatDpadRemainIndependent() {
        val translator = GamepadTranslator()
        translator.applyButton(ControllerButton.UP, true)
        val active = translator.updateNormalizedMotion(
            PhysicalGamepadNormalizer.Stick(0f, 0f),
            PhysicalGamepadNormalizer.Stick(0f, 0f),
            0f, 0f, setOf(ControllerButton.RIGHT)
        )
        assertEquals(setOf(ControllerButton.UP, ControllerButton.RIGHT), active.buttons)
        val afterKeyRelease = translator.applyButton(ControllerButton.UP, false)
        assertEquals(setOf(ControllerButton.RIGHT), afterKeyRelease.buttons)
        assertEquals(ControllerState(), translator.releaseAll())
    }

    @Test fun invalidNormalizedSamplesCannotLeakNanOrInfinity() {
        val translator = GamepadTranslator()
        val state = translator.updateNormalizedMotion(
            PhysicalGamepadNormalizer.Stick(Float.NaN, Float.POSITIVE_INFINITY),
            PhysicalGamepadNormalizer.Stick(-.5f, .25f),
            Float.NaN,
            Float.POSITIVE_INFINITY,
            setOf(ControllerButton.LEFT)
        )
        assertEquals(0f, state.leftX, 0f)
        assertEquals(0f, state.leftY, 0f)
        assertEquals(0f, state.leftTrigger, 0f)
        assertEquals(0f, state.rightTrigger, 0f)
        assertTrue(ControllerButton.LEFT in state.buttons)
    }
}
