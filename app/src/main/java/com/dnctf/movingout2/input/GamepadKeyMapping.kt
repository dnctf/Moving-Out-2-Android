package com.dnctf.movingout2.input

import android.view.KeyEvent

/** Stable Android key-code to canonical button mapping; pure and testable. */
object GamepadKeyMapping {
    private val mappings = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to ControllerButton.A, KeyEvent.KEYCODE_BUTTON_B to ControllerButton.B,
        KeyEvent.KEYCODE_BUTTON_X to ControllerButton.X, KeyEvent.KEYCODE_BUTTON_Y to ControllerButton.Y,
        KeyEvent.KEYCODE_BUTTON_L1 to ControllerButton.LB, KeyEvent.KEYCODE_BUTTON_R1 to ControllerButton.RB,
        KeyEvent.KEYCODE_BUTTON_L2 to ControllerButton.LT, KeyEvent.KEYCODE_BUTTON_R2 to ControllerButton.RT,
        KeyEvent.KEYCODE_BUTTON_START to ControllerButton.START, KeyEvent.KEYCODE_BUTTON_SELECT to ControllerButton.BACK,
        KeyEvent.KEYCODE_BUTTON_THUMBL to ControllerButton.L3, KeyEvent.KEYCODE_BUTTON_THUMBR to ControllerButton.R3,
        KeyEvent.KEYCODE_DPAD_UP to ControllerButton.UP, KeyEvent.KEYCODE_DPAD_DOWN to ControllerButton.DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to ControllerButton.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to ControllerButton.RIGHT
    )
    fun mapKeyCode(keyCode: Int): ControllerButton? = mappings[keyCode]
}
