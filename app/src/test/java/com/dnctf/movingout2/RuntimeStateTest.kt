package com.dnctf.movingout2

import com.dnctf.movingout2.runtime.RuntimeState
import com.dnctf.movingout2.runtime.RuntimeStateTransitions
import org.junit.Assert.*
import org.junit.Test

class RuntimeStateTest {
    @Test fun permitsReadyStartAndRunningTransitions() {
        assertEquals(RuntimeState.STARTING, RuntimeStateTransitions.transition(RuntimeState.READY, RuntimeState.STARTING))
        assertEquals(RuntimeState.RUNNING, RuntimeStateTransitions.transition(RuntimeState.STARTING, RuntimeState.RUNNING))
    }
    @Test fun rejectsRunningFromNotInstalled() {
        assertFalse(RuntimeStateTransitions.canTransition(RuntimeState.NOT_INSTALLED, RuntimeState.RUNNING))
    }
    @Test(expected = IllegalArgumentException::class) fun throwsOnIllegalTransition() {
        RuntimeStateTransitions.transition(RuntimeState.NOT_INSTALLED, RuntimeState.RUNNING)
    }
}