package com.dnctf.movingout2.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualControllerTopLayoutTest {
    @Test
    fun upperControlsStayInsideAndDoNotOverlapAcrossOrientationsAndScales() {
        val sizes = listOf(
            320f to 720f,
            360f to 800f,
            720f to 320f,
            800f to 360f,
            1024f to 600f
        )
        val scales = listOf(.65f, 1f, 1.25f, 1.5f)

        sizes.forEach { (width, height) ->
            scales.forEach { scale ->
                val unit = minOf(width, height) * .12f * scale
                val controls = VirtualControllerTopLayout.calculate(width, height, unit).all()
                controls.forEachIndexed { index, bounds ->
                    assertTrue("left edge outside width=$width scale=$scale", bounds.left >= -0.001f)
                    assertTrue("right edge outside width=$width scale=$scale", bounds.right <= width + 0.001f)
                    assertTrue("top edge outside height=$height scale=$scale", bounds.top >= -0.001f)
                    assertTrue("bottom edge outside height=$height scale=$scale", bounds.bottom <= height + 0.001f)
                    for (other in index + 1 until controls.size) {
                        assertFalse(
                            "controls $index and $other overlap at $width x $height, scale=$scale",
                            bounds.overlaps(controls[other])
                        )
                    }
                }
            }
        }
    }

    @Test
    fun upperControlsRemainInStableLeftToRightOrder() {
        val layout = VirtualControllerTopLayout.calculate(360f, 800f, 360f * .12f * 1.5f)
        val centers = layout.all().map { it.centerX }
        assertTrue(centers.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun extremeGlobalOffsetsStayInsideAndDoNotReintroduceOverlap() {
        listOf(320f to 720f, 720f to 320f).forEach { (width, height) ->
            listOf(-.15f, .15f).forEach { offset ->
                val unit = minOf(width, height) * .12f * 1.5f
                val controls = VirtualControllerTopLayout.calculate(width, height, unit, offset, offset).all()
                controls.forEachIndexed { index, bounds ->
                    assertTrue(bounds.left >= -0.001f)
                    assertTrue(bounds.right <= width + 0.001f)
                    assertTrue(bounds.top >= -0.001f)
                    assertTrue(bounds.bottom <= height + 0.001f)
                    for (other in index + 1 until controls.size) {
                        assertFalse(bounds.overlaps(controls[other]))
                    }
                }
            }
        }
    }

    @Test
    fun invalidGeometryIsRejectedBeforeHitTesting() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerTopLayout.calculate(Float.NaN, 800f, 48f)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            VirtualControllerTopLayout.calculate(360f, 800f, 0f)
        }
    }
}
