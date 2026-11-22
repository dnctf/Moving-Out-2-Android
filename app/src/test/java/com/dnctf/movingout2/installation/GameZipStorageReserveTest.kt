package com.dnctf.movingout2.installation

import org.junit.Assert.*
import org.junit.Test

class GameZipStorageReserveTest {
    @Test fun exactRequiredBytesAreAccepted() {
        assertTrue(gameZipStorageReserveSatisfied(100, 100))
    }

    @Test fun insufficientBytesAreRejected() {
        assertFalse(gameZipStorageReserveSatisfied(99, 100))
    }

    @Test fun negativeRequirementIsRejected() {
        assertFalse(gameZipStorageReserveSatisfied(100, -1))
    }

    @Test fun expandedBytesAndReserveAreAddedAfterZipStaging() {
        assertEquals(300L, gameZipRequiredSpace(200, 100))
    }

    @Test fun negativeSpaceComponentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { gameZipRequiredSpace(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { gameZipRequiredSpace(100, -1) }
    }

    @Test fun spaceRequirementOverflowIsRejected() {
        assertThrows(ArithmeticException::class.java) { gameZipRequiredSpace(Long.MAX_VALUE, 1) }
    }
}
