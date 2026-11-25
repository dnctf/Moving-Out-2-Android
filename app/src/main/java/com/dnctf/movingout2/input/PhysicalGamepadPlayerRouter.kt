package com.dnctf.movingout2.input

/** Assigns live Android device IDs to distinct XInput user slots, freeing slots on disconnect. */
class PhysicalGamepadPlayerRouter(private val maxPlayers: Int = XInputTransport.MAX_PLAYERS) {
    private val slots = mutableMapOf<Int, Int>()

    init {
        require(maxPlayers in 1..XInputTransport.MAX_PLAYERS) { "Invalid XInput player count." }
    }

    @Synchronized
    fun connect(deviceId: Int): Int? {
        slots[deviceId]?.let { return it }
        val occupied = slots.values.toSet()
        val free = (0 until maxPlayers).firstOrNull { it !in occupied } ?: return null
        slots[deviceId] = free
        return free
    }

    @Synchronized
    fun disconnect(deviceId: Int): Int? = slots.remove(deviceId)

    @Synchronized
    fun playerIndex(deviceId: Int): Int? = slots[deviceId]

    @Synchronized
    fun clear() = slots.clear()

    @Synchronized
    fun deviceCount(): Int = slots.size
}
