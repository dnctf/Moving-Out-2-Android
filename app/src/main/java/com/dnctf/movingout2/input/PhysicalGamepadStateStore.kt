package com.dnctf.movingout2.input

/** Pure state registry for hot-plug/disconnect tests and aggregate single-player input. */
class PhysicalGamepadStateStore {
    private val states = mutableMapOf<Int, ControllerState>()
    fun register(deviceId: Int) { states.putIfAbsent(deviceId, ControllerState()) }
    fun update(deviceId: Int, state: ControllerState) { if (deviceId in states) states[deviceId] = state }
    fun resetDevice(deviceId: Int) { if (deviceId in states) states[deviceId] = ControllerState() }
    fun remove(deviceId: Int) { states.remove(deviceId) }
    fun clear() { states.clear() }
    fun deviceCount() = states.size
    fun aggregate(): ControllerState {
        val all = states.values
        fun strongest(selector: (ControllerState) -> Float): Float =
            all.maxByOrNull { kotlin.math.abs(selector(it)) }?.let(selector) ?: 0f
        return ControllerState(
            buttons = all.flatMap { it.buttons }.toSet(),
            leftX = strongest { it.leftX }, leftY = strongest { it.leftY },
            rightX = strongest { it.rightX }, rightY = strongest { it.rightY },
            leftTrigger = all.maxOfOrNull { it.leftTrigger } ?: 0f,
            rightTrigger = all.maxOfOrNull { it.rightTrigger } ?: 0f
        )
    }
}
