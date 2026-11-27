package com.dnctf.movingout2.input

import kotlin.math.min

/**
 * Pure responsive layout for the six upper-row controls.
 *
 * Widths are expressed in the same height-derived unit as the other virtual controls. The row is
 * fitted as one group so BACK/START and the shoulder/trigger hit zones never overlap at high scale
 * settings or on narrow portrait screens.
 */
object VirtualControllerTopLayout {
    data class Bounds(
        val centerX: Float,
        val centerY: Float,
        val halfWidth: Float,
        val halfHeight: Float
    ) {
        val left: Float get() = centerX - halfWidth
        val top: Float get() = centerY - halfHeight
        val right: Float get() = centerX + halfWidth
        val bottom: Float get() = centerY + halfHeight

        fun overlaps(other: Bounds): Boolean =
            left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    data class Layout(
        val leftBumper: Bounds,
        val leftTrigger: Bounds,
        val back: Bounds,
        val start: Bounds,
        val rightTrigger: Bounds,
        val rightBumper: Bounds
    ) {
        fun all(): List<Bounds> = listOf(leftBumper, leftTrigger, back, start, rightTrigger, rightBumper)
    }

    fun calculate(width: Float, height: Float, unit: Float, offsetX: Float = 0f, offsetY: Float = 0f): Layout {
        require(width.isFinite() && width > 0f) { "Controller width must be finite and positive." }
        require(height.isFinite() && height > 0f) { "Controller height must be finite and positive." }
        require(unit.isFinite() && unit > 0f) { "Controller unit must be finite and positive." }

        val halfWidthUnits = floatArrayOf(.55f, .25f, .50f, .50f, .25f, .55f)
        val halfHeightUnits = floatArrayOf(.30f, .30f, .22f, .22f, .30f, .30f)
        val gap = unit * .12f
        val requiredWidth = halfWidthUnits.sum() * 2f * unit + gap * (halfWidthUnits.size - 1)
        val margin = min(width * .03f, unit * .12f)
        val availableWidth = (width - margin * 2f).coerceAtLeast(0f)
        val fit = if (requiredWidth > 0f) min(1f, availableWidth / requiredWidth) else 1f
        val fittedWidth = requiredWidth * fit
        var cursor = (width - fittedWidth) / 2f

        fun control(index: Int, yFraction: Float): Bounds {
            val halfWidth = halfWidthUnits[index] * unit * fit
            cursor += halfWidth
            val centerX = cursor
            cursor += halfWidth
            if (index < halfWidthUnits.lastIndex) cursor += gap * fit
            return Bounds(
                centerX = centerX,
                centerY = height * yFraction,
                halfWidth = halfWidth,
                halfHeight = halfHeightUnits[index] * unit * fit
            )
        }

        val base = Layout(
            leftBumper = control(0, .10f),
            leftTrigger = control(1, .10f),
            back = control(2, .12f),
            start = control(3, .12f),
            rightTrigger = control(4, .10f),
            rightBumper = control(5, .10f)
        )
        val controls = base.all()
        val minLeft = controls.minOf { it.left }
        val maxRight = controls.maxOf { it.right }
        val minTop = controls.minOf { it.top }
        val maxBottom = controls.maxOf { it.bottom }
        val requestedX = if (offsetX.isFinite()) offsetX * width else 0f
        val requestedY = if (offsetY.isFinite()) offsetY * height else 0f
        val shiftX = requestedX.coerceIn(-minLeft, width - maxRight)
        val shiftY = requestedY.coerceIn(-minTop, height - maxBottom)
        fun shifted(bounds: Bounds) = bounds.copy(
            centerX = bounds.centerX + shiftX,
            centerY = bounds.centerY + shiftY
        )
        return Layout(
            leftBumper = shifted(base.leftBumper),
            leftTrigger = shifted(base.leftTrigger),
            back = shifted(base.back),
            start = shifted(base.start),
            rightTrigger = shifted(base.rightTrigger),
            rightBumper = shifted(base.rightBumper)
        )
    }
}
