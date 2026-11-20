package com.dnctf.movingout2.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** Converts Android key/axis events into the app's canonical gamepad state. */
class GamepadTranslator(private val deadzone: Float = 0.18f) {
    private val buttons = mutableSetOf<ControllerButton>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f

    fun onKey(event: KeyEvent): ControllerState? {
        val mapped = keyMap[event.keyCode] ?: return null
        if (event.action == KeyEvent.ACTION_DOWN) buttons.add(mapped) else if (event.action == KeyEvent.ACTION_UP) buttons.remove(mapped)
        return snapshot()
    }

    fun onMotion(event: MotionEvent): ControllerState? {
        if (event.source and (InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD) == 0) return null
        lx = normalize(event.getAxisValue(MotionEvent.AXIS_X)); ly = normalize(event.getAxisValue(MotionEvent.AXIS_Y))
        rx = normalize(event.getAxisValue(MotionEvent.AXIS_Z)); ry = normalize(event.getAxisValue(MotionEvent.AXIS_RZ))
        lt = trigger(event.getAxisValue(MotionEvent.AXIS_LTRIGGER).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_BRAKE))
        rt = trigger(event.getAxisValue(MotionEvent.AXIS_RTRIGGER).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_GAS))
        setDpad(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        return snapshot()
    }

    fun releaseAll(): ControllerState { buttons.clear(); lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f; return snapshot() }
    private fun normalize(v: Float): Float = if (abs(v) < deadzone) 0f else (if (v > 0) (v-deadzone)/(1-deadzone) else (v+deadzone)/(1-deadzone)).coerceIn(-1f,1f)
    private fun trigger(v: Float): Float = if (v < 0f) ((v + 1f) / 2f).coerceIn(0f,1f) else v.coerceIn(0f,1f)
    private fun setDpad(x: Float, y: Float) {
        listOf(ControllerButton.LEFT,ControllerButton.RIGHT,ControllerButton.UP,ControllerButton.DOWN).forEach { buttons.remove(it) }
        if (x < -0.5f) buttons.add(ControllerButton.LEFT); if (x > 0.5f) buttons.add(ControllerButton.RIGHT)
        if (y < -0.5f) buttons.add(ControllerButton.UP); if (y > 0.5f) buttons.add(ControllerButton.DOWN)
    }
    private fun snapshot() = ControllerState(buttons.toSet(), lx,ly,rx,ry,lt,rt)
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
