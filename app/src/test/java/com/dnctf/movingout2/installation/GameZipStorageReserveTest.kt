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
}
