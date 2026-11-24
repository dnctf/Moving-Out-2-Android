package com.dnctf.movingout2.input

import kotlin.math.sqrt

/** Pure JVM touch controller math/state. A pointer owns one control group at a time. */
class VirtualControllerEngine(private val deadzone: Float = 0.12f) {
    data class Stick(val x: Float, val y: Float)
    data class State(
        val buttons: Set<ControllerButton> = emptySet(),
        val leftX: Float = 0f, val leftY: Float = 0f,
        val rightX: Float = 0f, val rightY: Float = 0f,
        val leftTrigger: Float = 0f, val rightTrigger: Float = 0f
    )

    private val buttonPointers = mutableMapOf<ControllerButton, Int>()
    private val pointerButtons = mutableMapOf<Int, MutableSet<ControllerButton>>()
    private var dpadPointer: Int? = null
    private var leftPointer: Int? = null
    private var rightPointer: Int? = null
    private var leftTriggerPointer: Int? = null
    private var rightTriggerPointer: Int? = null
    private var left = Stick(0f, 0f)
    private var right = Stick(0f, 0f)
    private var lt = 0f
    private var rt = 0f

    fun claimButton(pointerId: Int, button: ControllerButton): Boolean {
        if (button in DPAD_BUTTONS || pointerButtons.containsKey(pointerId) || ownsAny(pointerId) ||
            buttonPointers.containsKey(button)) return false
        buttonPointers[button] = pointerId
        pointerButtons[pointerId] = mutableSetOf(button)
        return true
    }

    /** Claims the D-pad as one pointer-owned group; an empty set represents its neutral center. */
    fun claimDpad(pointerId: Int, buttons: Set<ControllerButton>): Boolean {
        if (dpadPointer != null || ownsAny(pointerId)) return false
        dpadPointer = pointerId
        pointerButtons[pointerId] = mutableSetOf()
        updateDpad(pointerId, buttons)
        return true
    }

    /** Updates all cardinal directions atomically, allowing diagonal D-pad combinations. */
    fun updateDpad(pointerId: Int, buttons: Set<ControllerButton>): Boolean {
        if (dpadPointer != pointerId) return false
        val owned = pointerButtons[pointerId] ?: return false
        owned.toList().forEach { button ->
            if (buttonPointers[button] == pointerId) buttonPointers.remove(button)
        }
        owned.clear()
        buttons.filter { it in DPAD_BUTTONS }.forEach { button ->
            buttonPointers[button] = pointerId
            owned.add(button)
        }
        return true
    }

    fun ownsDpad(pointerId: Int): Boolean = dpadPointer == pointerId

    fun claimStick(pointerId: Int, rightStick: Boolean = false): Boolean {
        if (ownsAny(pointerId)) return false
        val current = if (rightStick) rightPointer else leftPointer
        if (current != null) return false
        if (rightStick) rightPointer = pointerId else leftPointer = pointerId
        return true
    }

    fun claimTrigger(pointerId: Int, right: Boolean): Boolean {
        if (ownsAny(pointerId)) return false
        val current = if (right) rightTriggerPointer else leftTriggerPointer
        if (current != null) return false
        if (right) rightTriggerPointer = pointerId else leftTriggerPointer = pointerId
        return true
    }

    fun ownsStick(pointerId: Int, rightStick: Boolean = false): Boolean =
        (if (rightStick) rightPointer else leftPointer) == pointerId

    fun moveStick(pointerId: Int, dx: Float, dy: Float, radius: Float, rightStick: Boolean = false): Stick {
        if (!ownsStick(pointerId, rightStick)) return if (rightStick) right else left
        if (radius <= 0f || !radius.isFinite() || !dx.isFinite() || !dy.isFinite()) {
            val neutral = Stick(0f, 0f)
            if (rightStick) right = neutral else left = neutral
            return neutral
        }
        val nx = dx / radius
        val ny = dy / radius
        val magnitude = sqrt(nx * nx + ny * ny)
        val factor = if (magnitude > 1f) 1f / magnitude else 1f
        val out = radialDeadzone(nx * factor, ny * factor, deadzone)
        if (rightStick) right = out else left = out
        return out
    }

    fun setTrigger(pointerId: Int, value: Float, right: Boolean): Boolean {
        if ((if (right) rightTriggerPointer else leftTriggerPointer) != pointerId) return false
        val v = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (right) rt = v else lt = v
        return true
    }

    fun releasePointer(pointerId: Int): State {
        pointerButtons.remove(pointerId)?.forEach { button ->
            if (buttonPointers[button] == pointerId) buttonPointers.remove(button)
        }
        if (dpadPointer == pointerId) dpadPointer = null
        if (leftPointer == pointerId) { leftPointer = null; left = Stick(0f, 0f) }
        if (rightPointer == pointerId) { rightPointer = null; right = Stick(0f, 0f) }
        if (leftTriggerPointer == pointerId) { leftTriggerPointer = null; lt = 0f }
        if (rightTriggerPointer == pointerId) { rightTriggerPointer = null; rt = 0f }
        return snapshot()
    }

    fun releaseAll(): State {
        pointerButtons.clear()
        buttonPointers.clear()
        dpadPointer = null
        leftPointer = null
        rightPointer = null
        leftTriggerPointer = null
        rightTriggerPointer = null
        left = Stick(0f, 0f)
        right = Stick(0f, 0f)
        lt = 0f
        rt = 0f
        return snapshot()
    }

    fun snapshot(): State = State(
        buttons = buttonPointers.keys.toSet(),
        leftX = left.x, leftY = left.y, rightX = right.x, rightY = right.y,
        leftTrigger = if (ControllerButton.LT in buttonPointers) 1f else lt,
        rightTrigger = if (ControllerButton.RT in buttonPointers) 1f else rt
    )

    private fun ownsAny(id: Int): Boolean =
        pointerButtons.containsKey(id) || dpadPointer == id || leftPointer == id || rightPointer == id ||
            leftTriggerPointer == id || rightTriggerPointer == id

    companion object {
        private val DPAD_BUTTONS = setOf(
            ControllerButton.UP, ControllerButton.DOWN, ControllerButton.LEFT, ControllerButton.RIGHT
        )

        fun radialDeadzone(x: Float, y: Float, deadzone: Float = 0.12f): Stick {
            if (!x.isFinite() || !y.isFinite()) return Stick(0f, 0f)
            val d = deadzone.takeIf { it.isFinite() }?.coerceIn(0f, 0.99f) ?: 0.12f
            val magnitude = sqrt(x * x + y * y).coerceAtMost(1f)
            if (magnitude <= d) return Stick(0f, 0f)
            val scaled = ((magnitude - d) / (1f - d)).coerceIn(0f, 1f)
            val originalMagnitude = sqrt(x * x + y * y)
            if (originalMagnitude == 0f) return Stick(0f, 0f)
            return Stick(
                (x / originalMagnitude * scaled).coerceIn(-1f, 1f),
                (y / originalMagnitude * scaled).coerceIn(-1f, 1f)
            )
        }
    }
}
