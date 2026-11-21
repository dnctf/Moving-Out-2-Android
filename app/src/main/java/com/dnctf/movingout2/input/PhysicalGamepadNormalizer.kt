package com.dnctf.movingout2.input

import kotlin.math.abs
import kotlin.math.sqrt

/** Platform-neutral normalization for Android gamepad samples and disconnect safety. */
class PhysicalGamepadNormalizer(private val deadzone: Float = 0.12f) {
    data class Sample(
        val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
        val lt: Float = 0f, val rt: Float = 0f, val buttons: Set<ControllerButton> = emptySet()
    )
    private var state = Sample()
    fun update(leftX: Float, leftY: Float, rightX: Float, rightY: Float,
               leftTrigger: Float, rightTrigger: Float, buttons: Set<ControllerButton>): Sample {
        state = Sample(radial(leftX,leftY).x, radial(leftX,leftY).y, radial(rightX,rightY).x, radial(rightX,rightY).y,
            trigger(leftTrigger), trigger(rightTrigger), buttons.toSet())
        return state
    }
    fun reset(): Sample { state = Sample(); return state }
    fun snapshot() = state
    private fun trigger(v: Float) = if (v.isFinite()) v.coerceIn(0f,1f) else 0f
    private fun radial(x: Float,y: Float): Stick = normalizeStick(x, y, deadzone)
    data class Stick(val x:Float,val y:Float)
    companion object {
        fun normalizeStick(x: Float, y: Float, deadzone: Float = 0.12f): Stick {
            if (!x.isFinite() || !y.isFinite()) return Stick(0f, 0f)
            val magnitude = sqrt(x * x + y * y)
            val clampedMagnitude = magnitude.coerceAtMost(1f)
            val zone = deadzone.coerceIn(0f, 0.99f)
            if (clampedMagnitude <= zone || magnitude == 0f) return Stick(0f, 0f)
            val scaled = ((clampedMagnitude - zone) / (1f - zone)).coerceIn(0f, 1f)
            return Stick((x / magnitude * scaled).coerceIn(-1f, 1f), (y / magnitude * scaled).coerceIn(-1f, 1f))
        }
        /** Converts vendor-advertised stick axis ranges to a centered [-1,1] value. */
        fun normalizeAxisInRange(raw: Float, min: Float, max: Float): Float {
            if (!raw.isFinite() || !min.isFinite() || !max.isFinite() || max <= min) return 0f
            val center = if (min < 0f && max > 0f) 0f else (min + max) / 2f
            val value = raw.coerceIn(min, max)
            return when {
                value < center && center > min -> ((value - center) / (center - min)).coerceIn(-1f, 0f)
                value > center && max > center -> ((value - center) / (max - center)).coerceIn(0f, 1f)
                else -> 0f
            }
        }
        fun normalizeTrigger(raw: Float, digital: Boolean = false): Float = when {
            digital -> if (raw > 0f) 1f else 0f
            !raw.isFinite() -> 0f
            raw < 0f -> ((raw + 1f) / 2f).coerceIn(0f, 1f)
            else -> raw.coerceIn(0f, 1f)
        }
        fun normalizeTriggerInRange(raw: Float, min: Float, max: Float): Float {
            if (!raw.isFinite() || !min.isFinite() || !max.isFinite() || max <= min) return 0f
            return ((raw - min) / (max - min)).coerceIn(0f, 1f)
        }
        fun hatButtons(x: Float, y: Float): Set<ControllerButton> = buildSet {
            if (x < -0.5f) add(ControllerButton.LEFT)
            if (x > 0.5f) add(ControllerButton.RIGHT)
            if (y < -0.5f) add(ControllerButton.UP)
            if (y > 0.5f) add(ControllerButton.DOWN)
        }
    }
}
