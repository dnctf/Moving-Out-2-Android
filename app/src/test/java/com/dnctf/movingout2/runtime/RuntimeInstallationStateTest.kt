package com.dnctf.movingout2.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInstallationStateTest {
    @Test fun launchRequiresRuntimeExecAndGame() {
        assertTrue(RuntimeInstallationState(true, true, true).canLaunch)
        assertFalse(RuntimeInstallationState(false, true, true).canLaunch)
        assertFalse(RuntimeInstallationState(true, false, true).canLaunch)
        assertFalse(RuntimeInstallationState(true, true, false).canLaunch)
    }

    @Test fun reportsSpecificBlockingReason() {
        assertEquals("BLOCKED_RUNTIME_NOT_READY", RuntimeInstallationState(false, false, false).launchReadiness)
        assertEquals("BLOCKED_NATIVE_EXEC_PROBE", RuntimeInstallationState(true, false, true).launchReadiness)
        assertEquals("BLOCKED_GAME_FILES_MISSING", RuntimeInstallationState(true, true, false).launchReadiness)
        assertEquals("READY", RuntimeInstallationState(true, true, true).launchReadiness)
    }
}
