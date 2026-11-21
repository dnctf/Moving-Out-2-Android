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
        fun safe(v: Float) = if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f
        fun strongestStick(x: (ControllerState) -> Float, y: (ControllerState) -> Float): Pair<Float, Float> {
            val selected = all.maxByOrNull { state ->
                val sx = safe(x(state)); val sy = safe(y(state))
                sx * sx + sy * sy
            } ?: return 0f to 0f
            val sx = safe(x(selected)); val sy = safe(y(selected))
            val magnitude = kotlin.math.sqrt(sx * sx + sy * sy)
            if (magnitude <= 1f || magnitude == 0f) return sx to sy
            return (sx / magnitude) to (sy / magnitude)
        }
        val left = strongestStick({ it.leftX }, { it.leftY })
        val right = strongestStick({ it.rightX }, { it.rightY })
        return ControllerState(
            buttons = all.flatMap { it.buttons }.toSet(),
            leftX = left.first, leftY = left.second,
            rightX = right.first, rightY = right.second,
            leftTrigger = all.maxOfOrNull { safe(it.leftTrigger).coerceIn(0f, 1f) } ?: 0f,
            rightTrigger = all.maxOfOrNull { safe(it.rightTrigger).coerceIn(0f, 1f) } ?: 0f
        )
    }
}
