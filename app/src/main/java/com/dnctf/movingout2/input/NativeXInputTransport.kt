package com.dnctf.movingout2.input

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class NativeXInputTransport(forceFallbackForTesting: Boolean = false) : XInputTransport {
    private val nativeAvailable = !forceFallbackForTesting && loadLibrary()
    private val fallback = ConcurrentHashMap<Int, ByteArray>()
    @Volatile private var initialized = false
    @Volatile private var sharedPath: String? = null

    override fun init(shmFilePath: String): Boolean {
        initialized = false
        sharedPath = null
        if (shmFilePath.isBlank()) return false
        if (nativeAvailable) {
            val ok = runCatching { nativeInitSharedMemory(shmFilePath) }.getOrDefault(false)
            if (!ok) return false
        } else {
            for (i in 0 until XInputTransport.MAX_PLAYERS) fallback[i] = neutralPacket()
        }
        sharedPath = shmFilePath
        initialized = true
        return true
    }

    override fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean {
        if (!initialized || playerIndex !in 0 until XInputTransport.MAX_PLAYERS || packet.size != XInputTransport.PACKET_SIZE_BYTES) return false
        val effective = if (connected) packet.copyOf() else neutralPacket()
        if (nativeAvailable) {
            val buffer = ByteBuffer.allocateDirect(XInputTransport.PACKET_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(effective).flip()
            return runCatching { nativeWriteSerializedPacket(playerIndex, connected, buffer) }.getOrDefault(false)
        }
        fallback[playerIndex] = effective
        return true
    }

    override fun destroy() {
        if (!initialized) return
        if (nativeAvailable) runCatching { nativeDestroySharedMemory() }
        else for (i in 0 until XInputTransport.MAX_PLAYERS) fallback[i] = neutralPacket()
        initialized = false
        sharedPath = null
    }

    override fun isNativeAvailable() = nativeAvailable
    override fun isInitialized() = initialized
    fun getFallbackSnapshot(playerIndex: Int): ByteArray? = fallback[playerIndex]?.copyOf()
    fun sharedMemoryPath(): String? = sharedPath

    private fun neutralPacket() = ByteArray(XInputTransport.PACKET_SIZE_BYTES)
    private fun loadLibrary(): Boolean = try {
        System.loadLibrary("mo2_input_bridge")
        true
    } catch (_: LinkageError) { false } catch (_: SecurityException) { false }
    private external fun nativeInitSharedMemory(path: String): Boolean
    private external fun nativeWriteSerializedPacket(playerIndex: Int, connected: Boolean, packet: ByteBuffer): Boolean
    private external fun nativeDestroySharedMemory(): Boolean
}
