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

    @Test fun extractionReserveChecksAccumulateAcrossSmallEntries() {
        val interval = 4L * 1024L * 1024L
        assertFalse(gameZipStorageCheckDue(3L * 1024L * 1024L, 0, interval))
        assertTrue(gameZipStorageCheckDue(4L * 1024L * 1024L, 0, interval))
    }

    @Test fun invalidStorageCheckCountersAreRejected() {
        assertFalse(gameZipStorageCheckDue(-1, 0, 4))
        assertFalse(gameZipStorageCheckDue(4, 5, 1))
        assertFalse(gameZipStorageCheckDue(5, 0, 0))
    }
}
