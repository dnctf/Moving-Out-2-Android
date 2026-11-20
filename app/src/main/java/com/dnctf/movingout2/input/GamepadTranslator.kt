package com.dnctf.movingout2.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** Android controller input normalizer with configurable deadzone and safe release handling. */
class GamepadTranslator(private val deadzone: Float = 0.18f) {
    private val buttons = mutableSetOf<ControllerButton>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f; private var lt = 0f; private var rt = 0f

    fun onKey(event: KeyEvent): ControllerState? {
        val button = keyMap[event.keyCode] ?: return null
        when (event.action) {
            KeyEvent.ACTION_DOWN -> buttons.add(button)
            KeyEvent.ACTION_UP -> buttons.remove(button)
            else -> return null
        }
        return snapshot()
    }

    fun onMotion(event: MotionEvent): ControllerState? {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) == 0) return null
        lx = normalizeAxis(event.getAxisValue(MotionEvent.AXIS_X))
        ly = normalizeAxis(event.getAxisValue(MotionEvent.AXIS_Y))
        rx = normalizeAxis(firstNonZero(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX))
        ry = normalizeAxis(firstNonZero(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)))
        lt = normalizeTrigger(firstNonZero(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE))
        rt = normalizeTrigger(firstNonZero(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS))
        updateHat(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        return snapshot()
    }

    fun releaseAll(): ControllerState {
        buttons.clear()
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f
        return snapshot()
    }

    private fun normalizeAxis(raw: Float): Float {
        if (!raw.isFinite() || abs(raw) <= deadzone) return 0f
        return (if (raw > 0f) (raw - deadzone) / (1f - deadzone) else (raw + deadzone) / (1f - deadzone)).coerceIn(-1f, 1f)
    }

    private fun normalizeTrigger(raw: Float): Float {
        if (!raw.isFinite()) return 0f
        return if (raw < 0f) ((raw + 1f) / 2f).coerceIn(0f, 1f) else raw.coerceIn(0f, 1f)
    }

    private fun firstNonZero(event: MotionEvent, primary: Int, alternate: Int): Float {
        val value = event.getAxisValue(primary)
        return if (value != 0f) value else event.getAxisValue(alternate)
    }

    private fun updateHat(x: Float, y: Float) {
        buttons.removeAll(setOf(ControllerButton.UP, ControllerButton.DOWN, ControllerButton.LEFT, ControllerButton.RIGHT))
        if (x < -0.5f) buttons.add(ControllerButton.LEFT)
        if (x > 0.5f) buttons.add(ControllerButton.RIGHT)
        if (y < -0.5f) buttons.add(ControllerButton.UP)
        if (y > 0.5f) buttons.add(ControllerButton.DOWN)
    }

    private fun snapshot() = ControllerState(buttons.toSet(), lx, ly, rx, ry, lt, rt)

    companion object {
        private val keyMap = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to ControllerButton.A, KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B,
            KeyEvent.KEYCODE_BUTTON_X to ControllerButton.X, KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to ControllerButton.LB, KeyEvent.KEYCODE_BUTTON_R1 to ControllerButton.RB,
            KeyEvent.KEYCODE_BUTTON_L2 to ControllerButton.LT, KeyEvent.KEYCODE_BUTTON_R2 to ControllerButton.RT,
            KeyEvent.KEYCODE_BUTTON_START to ControllerButton.START, KeyEvent.KEYCODE_BUTTON_SELECT to ControllerButton.BACK,
            KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerButton.L3, KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerButton.R3,
            KeyEvent.KEYCODE_DPAD_UP to ControllerButton.UP, KeyEvent.KEYCODE_DPAD_DOWN to ControllerButton.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to ControllerButton.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to ControllerButton.RIGHT
        )
    }
}
