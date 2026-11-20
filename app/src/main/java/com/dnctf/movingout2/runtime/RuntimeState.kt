package com.dnctf.movingout2.runtime

enum class RuntimeState {
    NOT_INSTALLED, READY, STARTING, RUNNING, STOPPING, STOPPED, FAILED
}

object RuntimeStateTransitions {
    private val allowed = mapOf(
        RuntimeState.NOT_INSTALLED to setOf(RuntimeState.READY),
        RuntimeState.READY to setOf(RuntimeState.STARTING, RuntimeState.NOT_INSTALLED),
        RuntimeState.STARTING to setOf(RuntimeState.RUNNING, RuntimeState.FAILED, RuntimeState.STOPPING),
        RuntimeState.RUNNING to setOf(RuntimeState.STOPPING, RuntimeState.FAILED),
        RuntimeState.STOPPING to setOf(RuntimeState.STOPPED, RuntimeState.FAILED),
        RuntimeState.STOPPED to setOf(RuntimeState.STARTING, RuntimeState.READY, RuntimeState.NOT_INSTALLED),
        RuntimeState.FAILED to setOf(RuntimeState.READY, RuntimeState.STARTING, RuntimeState.NOT_INSTALLED)
    )
    fun canTransition(from: RuntimeState, to: RuntimeState): Boolean = to in allowed[from].orEmpty()
    fun transition(from: RuntimeState, to: RuntimeState): RuntimeState {
        require(canTransition(from, to)) { "Invalid runtime transition: $from -> $to" }
        return to
    }
}