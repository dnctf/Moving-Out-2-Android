package com.dnctf.movingout2.input

import kotlin.math.min

/**
 * Pure responsive geometry for the virtual controller.
 *
 * The requested scale and global offsets are constrained against every visible hit zone.
 * This prevents the sticks, D-pad, face buttons and top row from being clipped or overlapping
 * when the device rotates or the user selects a large scale.
 */
object VirtualControllerGeometry {
    data class Point(val x: Float, val y: Float)

    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun overlaps(other: Bounds): Boolean =
            left < other.right && right > other.left && top < other.bottom && bottom > other.top

        fun shifted(dx: Float, dy: Float) =
            Bounds(left + dx, top + dy, right + dx, bottom + dy)
    }

    data class Layout(
        val unit: Float,
        val effectiveOffsetX: Float,
        val effectiveOffsetY: Float,
        val leftStickCenter: Point,
        val rightStickCenter: Point,
        val dpadCenter: Point,
        val stickRadius: Float,
        val dpadRadius: Float,
        val buttonBounds: Map<ControllerButton, Bounds>,
        val triggerBounds: Map<Boolean, Bounds>,
        /** Non-overlapping control-group hit bounds, including each upper-row control. */
        val interactiveBounds: List<Bounds>
    )

    fun calculate(
        width: Float,
        height: Float,
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): Layout {
        require(width.isFinite() && width > 0f) { "Controller width must be finite and positive." }
        require(height.isFinite() && height > 0f) { "Controller height must be finite and positive." }

        val safeScale = scale.takeIf { it.isFinite() }?.coerceIn(.65f, 1.5f) ?: 1f
        val requestedX = offsetX.takeIf { it.isFinite() }?.coerceIn(-.15f, .15f) ?: 0f
        val requestedY = offsetY.takeIf { it.isFinite() }?.coerceIn(-.15f, .15f) ?: 0f

        val landscape = width / height >= 1.35f
        val stickY = if (landscape) .76f else .72f
        val dpadY = if (landscape) .38f else .40f
        val faceY = .55f

        // Keep the visible circular hit zones separate while retaining the original relative
        // control sizes. max(dx, dy) allows portrait layouts to separate groups vertically and
        // landscape layouts to separate them horizontally.
        val maxUnit = minOf(
            width * .20f / 1.35f,                    // left stick's left edge
            width * .42f / 1.35f,                    // right stick's right edge
            width * .38f / 2.70f,                    // separation between the two sticks
            width * .22f / 1.18f,                    // D-pad's left edge
            width * .78f / 1.18f,                    // D-pad's right edge
            width * .84f / .92f,                     // face group left edge
            width * .16f / .92f,                     // face group right edge
            height * stickY / 1.35f,
            height * (1f - stickY) / 1.35f,
            height * dpadY / 1.18f,
            height * (1f - dpadY) / 1.18f,
            height * faceY / .92f,
            height * (1f - faceY) / .92f,
            height * (stickY - dpadY) / (1.35f + 1.18f),
            height * (dpadY - .10f) / (1.18f + .30f),
            height * (dpadY - .12f) / (1.18f + .22f),
            maxOf(width * .02f, height * (stickY - dpadY)) / (1.35f + 1.18f),
            maxOf(width * .36f, height * (stickY - dpadY)) / (1.35f + 1.18f),
            maxOf(width * .26f, height * (stickY - faceY)) / (1.35f + .92f)
        )
        val unit = min(min(width, height) * .12f * safeScale, maxUnit)
        require(unit.isFinite() && unit > 0f) { "Controller geometry cannot fit this view." }

        val stickRadius = unit * 1.35f
        val dpadRadius = unit * 1.18f
        val faceExtent = unit * .92f
        val leftBase = Point(width * .20f, height * stickY)
        val rightBase = Point(width * .58f, height * stickY)
        val dpadBase = Point(width * .22f, height * dpadY)
        val faceBase = Point(width * .84f, height * faceY)

        fun square(center: Point, radius: Float) = Bounds(
            center.x - radius, center.y - radius, center.x + radius, center.y + radius
        )
        val topBase = VirtualControllerTopLayout.calculate(width, height, unit).all().map {
            Bounds(it.left, it.top, it.right, it.bottom)
        }
        val baseGroups = listOf(
            square(leftBase, stickRadius),
            square(rightBase, stickRadius),
            square(dpadBase, dpadRadius),
            square(faceBase, faceExtent)
        ) + topBase

        val minShiftX = baseGroups.maxOf { -it.left }
        val maxShiftX = baseGroups.minOf { width - it.right }
        val minShiftY = baseGroups.maxOf { -it.top }
        val maxShiftY = baseGroups.minOf { height - it.bottom }
        require(minShiftX <= maxShiftX && minShiftY <= maxShiftY) {
            "Controller control groups do not fit inside the view."
        }
        val shiftX = (requestedX * width).coerceIn(minShiftX, maxShiftX)
        val shiftY = (requestedY * height).coerceIn(minShiftY, maxShiftY)

        val left = Point(leftBase.x + shiftX, leftBase.y + shiftY)
        val right = Point(rightBase.x + shiftX, rightBase.y + shiftY)
        val dpad = Point(dpadBase.x + shiftX, dpadBase.y + shiftY)
        val face = Point(faceBase.x + shiftX, faceBase.y + shiftY)
        val top = VirtualControllerTopLayout.calculate(width, height, unit, shiftX / width, shiftY / height)

        fun rect(cx: Float, cy: Float, halfWidth: Float, halfHeight: Float) =
            Bounds(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)

        val buttons = linkedMapOf<ControllerButton, Bounds>()
        val dpadSize = unit * .28f
        val dpadOffset = unit * .58f
        buttons[ControllerButton.UP] = rect(dpad.x, dpad.y - dpadOffset, dpadSize, dpadSize)
        buttons[ControllerButton.DOWN] = rect(dpad.x, dpad.y + dpadOffset, dpadSize, dpadSize)
        buttons[ControllerButton.LEFT] = rect(dpad.x - dpadOffset, dpad.y, dpadSize, dpadSize)
        buttons[ControllerButton.RIGHT] = rect(dpad.x + dpadOffset, dpad.y, dpadSize, dpadSize)

        val faceSize = unit * .30f
        val faceOffset = unit * .62f
        buttons[ControllerButton.Y] = rect(face.x, face.y - faceOffset, faceSize, faceSize)
        buttons[ControllerButton.A] = rect(face.x, face.y + faceOffset, faceSize, faceSize)
        buttons[ControllerButton.X] = rect(face.x - faceOffset, face.y, faceSize, faceSize)
        buttons[ControllerButton.B] = rect(face.x + faceOffset, face.y, faceSize, faceSize)

        fun VirtualControllerTopLayout.Bounds.toBounds() = Bounds(left, top, right, bottom)
        buttons[ControllerButton.LB] = top.leftBumper.toBounds()
        buttons[ControllerButton.BACK] = top.back.toBounds()
        buttons[ControllerButton.START] = top.start.toBounds()
        buttons[ControllerButton.RB] = top.rightBumper.toBounds()
        val triggers = mapOf(
            false to top.leftTrigger.toBounds(),
            true to top.rightTrigger.toBounds()
        )

        val interactive = listOf(
            square(left, stickRadius),
            square(right, stickRadius),
            square(dpad, dpadRadius),
            square(face, faceExtent)
        ) + top.all().map { it.toBounds() }

        return Layout(
            unit = unit,
            effectiveOffsetX = shiftX / width,
            effectiveOffsetY = shiftY / height,
            leftStickCenter = left,
            rightStickCenter = right,
            dpadCenter = dpad,
            stickRadius = stickRadius,
            dpadRadius = dpadRadius,
            buttonBounds = buttons,
            triggerBounds = triggers,
            interactiveBounds = interactive
        )
    }
}
