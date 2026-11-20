package com.dnctf.movingout2.input

/** Canonical controller state shared by touch and physical-device input. */
data class ControllerState(
    val buttons: Set<ControllerButton> = emptySet(),
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f
) {
    fun axis(value: Float) = value.coerceIn(-1f, 1f)
    fun trigger(value: Float) = value.coerceIn(0f, 1f)
    fun isPressed(button: ControllerButton) = button in buttons
}

enum class ControllerButton {
    UP, DOWN, LEFT, RIGHT, A, B, X, Y, LB, RB, LT, RT, START, BACK, L3, R3
}
