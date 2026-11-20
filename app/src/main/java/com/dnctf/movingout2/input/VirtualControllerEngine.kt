package com.dnctf.movingout2.input

import kotlin.math.sqrt

/** Pure JVM touch controller math/state. Pointer IDs are owned per control. */
class VirtualControllerEngine(private val deadzone: Float = 0.12f) {
    data class Stick(val x: Float, val y: Float)
    data class State(
        val buttons: Set<ControllerButton> = emptySet(),
        val leftX: Float = 0f,
        val leftY: Float = 0f,
        val rightX: Float = 0f,
        val rightY: Float = 0f,
        val leftTrigger: Float = 0f,
        val rightTrigger: Float = 0f
    )
    private val buttonPointers = mutableMapOf<ControllerButton, Int>()
    private val pointerButtons = mutableMapOf<Int, ControllerButton>()
    private var leftPointer: Int? = null
    private var rightPointer: Int? = null
    private var left = Stick(0f, 0f)
    private var right = Stick(0f, 0f)
    private var lt = 0f
    private var rt = 0f

    fun claimButton(pointerId: Int, button: ControllerButton): Boolean {
        if (buttonPointers.containsKey(button)) return false
        buttonPointers[button] = pointerId
        pointerButtons[pointerId] = button
        return true
    }
    fun claimStick(pointerId: Int, rightStick: Boolean = false): Boolean {
        val current = if (rightStick) rightPointer else leftPointer
        if (current != null) return false
        if (rightStick) rightPointer = pointerId else leftPointer = pointerId
        return true
    }
    fun moveStick(pointerId: Int, dx: Float, dy: Float, radius: Float, rightStick: Boolean = false): Stick {
        if (radius <= 0f || !radius.isFinite()) return Stick(0f, 0f)
        if ((if (rightStick) rightPointer else leftPointer) != pointerId) return if (rightStick) right else left
        val nx = dx / radius
        val ny = dy / radius
        val magnitude = sqrt(nx * nx + ny * ny)
        val clamped = if (magnitude > 1f) 1f / magnitude else 1f
        val x = nx * clamped
        val y = ny * clamped
        val out = radialDeadzone(x, y, deadzone)
        if (rightStick) right = out else left = out
        return out
    }
    fun setTrigger(pointerId: Int, value: Float, right: Boolean): Boolean {
        if (rightPointer != pointerId && leftPointer != pointerId) return false
        val v = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (right) rt = v else lt = v
        return true
    }
    fun releasePointer(pointerId: Int): State {
        pointerButtons.remove(pointerId)?.let { button ->
            if (buttonPointers[button] == pointerId) buttonPointers.remove(button)
        }
        if (leftPointer == pointerId) { leftPointer = null; left = Stick(0f, 0f) }
        if (rightPointer == pointerId) { rightPointer = null; right = Stick(0f, 0f) }
        // Triggers are cleared on every touch cancellation/up as a conservative safety rule.
        lt = 0f; rt = 0f
        return snapshot()
    }
    fun releaseAll(): State {
        pointerButtons.clear(); buttonPointers.clear(); leftPointer = null; rightPointer = null
        left = Stick(0f, 0f); right = Stick(0f, 0f); lt = 0f; rt = 0f
        return snapshot()
    }
    fun snapshot(): State = State(buttonPointers.keys.toSet(), left.x, left.y, right.x, right.y, lt, rt)

    companion object {
        fun radialDeadzone(x: Float, y: Float, deadzone: Float = 0.12f): Stick {
            if (!x.isFinite() || !y.isFinite()) return Stick(0f, 0f)
            val d = deadzone.coerceIn(0f, 0.99f)
            val mag = sqrt(x * x + y * y).coerceAtMost(1f)
            if (mag <= d) return Stick(0f, 0f)
            val scaled = ((mag - d) / (1f - d)).coerceIn(0f, 1f)
            val denom = sqrt(x * x + y * y)
            if (denom == 0f) return Stick(0f, 0f)
            return Stick((x / denom * scaled).coerceIn(-1f, 1f), (y / denom * scaled).coerceIn(-1f, 1f))
        }
    }
}
