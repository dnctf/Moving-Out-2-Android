package com.dnctf.movingout2.input

import kotlin.math.atan2
import kotlin.math.sqrt

/** Platform-neutral 8-way D-pad hit testing. Returns null outside the control circle. */
object VirtualDpadGeometry {
    /**
     * Returns cardinal buttons, a two-button diagonal, or an empty set in the neutral center.
     * Coordinates use Android's screen convention (positive Y points down).
     */
    fun hitTest(
        centerX: Float,
        centerY: Float,
        x: Float,
        y: Float,
        radius: Float,
        deadzoneRatio: Float = 0.20f
    ): Set<ControllerButton>? {
        if (!centerX.isFinite() || !centerY.isFinite() || !x.isFinite() || !y.isFinite() ||
            !radius.isFinite() || radius <= 0f) return null
        val dx = x - centerX
        val dy = y - centerY
        val distance = sqrt(dx * dx + dy * dy)
        if (distance > radius) return null
        val deadzone = deadzoneRatio.takeIf { it.isFinite() }?.coerceIn(0f, 0.9f) ?: 0.20f
        if (distance <= radius * deadzone) return emptySet()

        val degrees = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
        val sector = ((degrees + 22.5f) / 45f).toInt() % 8
        return when (sector) {
            0 -> setOf(ControllerButton.RIGHT)
            1 -> setOf(ControllerButton.DOWN, ControllerButton.RIGHT)
            2 -> setOf(ControllerButton.DOWN)
            3 -> setOf(ControllerButton.DOWN, ControllerButton.LEFT)
            4 -> setOf(ControllerButton.LEFT)
            5 -> setOf(ControllerButton.UP, ControllerButton.LEFT)
            6 -> setOf(ControllerButton.UP)
            else -> setOf(ControllerButton.UP, ControllerButton.RIGHT)
        }
    }
}
