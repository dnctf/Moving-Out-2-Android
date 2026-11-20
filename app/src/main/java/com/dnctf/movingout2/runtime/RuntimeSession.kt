package com.dnctf.movingout2.runtime

data class RuntimeSession(val id: String, var state: RuntimeState = RuntimeState.NOT_INSTALLED) {
    fun moveTo(next: RuntimeState) { state = RuntimeStateTransitions.transition(state, next) }
}