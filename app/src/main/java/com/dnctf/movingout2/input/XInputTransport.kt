package com.dnctf.movingout2.input

interface XInputTransport {
    fun init(shmFilePath: String): Boolean
    fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean
    fun destroy()
    fun isNativeAvailable(): Boolean
    fun isInitialized(): Boolean
    /** Monotonic count of XInput DLL reads; null means this transport cannot observe a reader. */
    fun readReaderHeartbeat(): Long? = null

    companion object {
        const val MAX_PLAYERS = 4
        const val PACKET_SIZE_BYTES = 12
    }
}
