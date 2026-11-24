package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class VirtualDpadGeometryTest {
    @Test fun cardinalDirectionsUseAndroidScreenCoordinates() {
        assertEquals(setOf(ControllerButton.RIGHT), VirtualDpadGeometry.hitTest(0f, 0f, .8f, 0f, 1f))
        assertEquals(setOf(ControllerButton.DOWN), VirtualDpadGeometry.hitTest(0f, 0f, 0f, .8f, 1f))
        assertEquals(setOf(ControllerButton.LEFT), VirtualDpadGeometry.hitTest(0f, 0f, -.8f, 0f, 1f))
        assertEquals(setOf(ControllerButton.UP), VirtualDpadGeometry.hitTest(0f, 0f, 0f, -.8f, 1f))
    }

    @Test fun diagonalSectorsReturnTwoButtonCombination() {
        assertEquals(
            setOf(ControllerButton.DOWN, ControllerButton.RIGHT),
            VirtualDpadGeometry.hitTest(0f, 0f, .7f, .7f, 1f)
        )
        assertEquals(
            setOf(ControllerButton.UP, ControllerButton.LEFT),
            VirtualDpadGeometry.hitTest(0f, 0f, -.7f, -.7f, 1f)
        )
    }

    @Test fun centerDeadzoneIsNeutralAndOutsideIsNotClaimed() {
        assertEquals(emptySet<ControllerButton>(), VirtualDpadGeometry.hitTest(0f, 0f, .05f, .05f, 1f))
        assertNull(VirtualDpadGeometry.hitTest(0f, 0f, 1.1f, 0f, 1f))
        assertNull(VirtualDpadGeometry.hitTest(0f, 0f, 0f, 0f, 0f))
    }

    @Test fun invalidCoordinatesAreRejected() {
        assertNull(VirtualDpadGeometry.hitTest(0f, 0f, Float.NaN, 1f, 1f))
    }
}
