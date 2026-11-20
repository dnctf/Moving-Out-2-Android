package com.dnctf.movingout2.input

interface XInputTransport {
    fun init(shmFilePath: String): Boolean
    fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean
    fun destroy()
    fun isNativeAvailable(): Boolean
    fun isInitialized(): Boolean

    companion object {
        const val MAX_PLAYERS = 4
        const val PACKET_SIZE_BYTES = 12
    }
}
