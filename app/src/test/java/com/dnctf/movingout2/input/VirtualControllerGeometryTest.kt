package com.dnctf.movingout2.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualControllerGeometryTest {
    private val sizes = listOf(
        320f to 720f,
        360f to 800f,
        720f to 320f,
        800f to 360f,
        1024f to 600f,
        1280f to 800f,
        1920f to 1080f
    )

    @Test
    fun everyControlGroupRemainsVisibleAndNonOverlappingAcrossLayouts() {
        val scales = listOf(.65f, 1f, 1.25f, 1.5f)
        val offsets = listOf(-.15f, 0f, .15f)
        sizes.forEach { (width, height) ->
            scales.forEach { scale ->
                offsets.forEach { offset ->
                    val layout = VirtualControllerGeometry.calculate(
                        width, height, scale, offsetX = offset, offsetY = offset
                    )
                    val bounds = layout.interactiveBounds
                    bounds.forEachIndexed { index, control ->
                        assertTrue("left edge outside $width x $height", control.left >= -.001f)
                        assertTrue("top edge outside $width x $height", control.top >= -.001f)
                        assertTrue("right edge outside $width x $height", control.right <= width + .001f)
                        assertTrue("bottom edge outside $width x $height", control.bottom <= height + .001f)
                        for (other in index + 1 until bounds.size) {
                            assertFalse(
                                "control groups $index and $other overlap at $width x $height scale=$scale offset=$offset",
                                control.overlaps(bounds[other])
                            )
                        }
                    }
                    assertTrue(layout.unit > 0f)
                    assertTrue(layout.effectiveOffsetX in -.15f.. .15f)
                    assertTrue(layout.effectiveOffsetY in -.15f.. .15f)
                }
            }
        }
    }

    @Test
    fun highScalePortraitLayoutFitsWithoutClippingTheLeftStickOrFaceButtons() {
        val layout = VirtualControllerGeometry.calculate(320f, 720f, 1.5f, .15f, -.15f)
        val left = layout.interactiveBounds[0]
        val face = layout.interactiveBounds[3]
        assertTrue(left.left >= 0f)
        assertTrue(face.right <= 320f)
        assertTrue(layout.unit < 320f * .12f * 1.5f)
    }

    @Test
    fun landscapeLayoutSeparatesDpadFromSticksAndTopRow() {
        val layout = VirtualControllerGeometry.calculate(1280f, 800f, 1.5f, 0f, 0f)
        val groups = layout.interactiveBounds
        assertFalse(groups[0].overlaps(groups[2]))
        assertFalse(groups[2].overlaps(groups[4]))
        assertFalse(groups[2].overlaps(groups[5]))
        assertFalse(groups[1].overlaps(groups[3]))
    }

    @Test
    fun invalidDimensionsAreRejectedBeforeHitTesting() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerGeometry.calculate(Float.NaN, 800f)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerGeometry.calculate(0f, 800f)
        }
    }
}
