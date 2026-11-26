package com.dnctf.movingout2.input

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * JNI owns one process-global mapping. A Kotlin lease registry prevents one transport instance
 * from unmapping that mapping while another instance still uses the same shared-memory path.
 */
class NativeXInputTransport(forceFallbackForTesting: Boolean = false) : XInputTransport {
    private val nativeAvailable = !forceFallbackForTesting && loadLibrary()
    private val fallback = ConcurrentHashMap<Int, ByteArray>()
    @Volatile private var initialized = false
    @Volatile private var sharedPath: String? = null

    @Synchronized
    override fun init(shmFilePath: String): Boolean {
        if (shmFilePath.isBlank()) {
            destroy()
            neutralizeFallback()
            return false
        }
        if (initialized && sharedPath == shmFilePath) return true
        if (initialized) {
            destroy()
            if (initialized) return false
        }

        if (nativeAvailable) {
            val acquired = synchronized(nativeLeaseLock) {
                when (nativeLeaseRegistry.acquire(shmFilePath)) {
                    NativeMappingLeaseRegistry.AcquireResult.FIRST -> {
                        val ok = runCatching { nativeInitSharedMemory(shmFilePath) }.getOrDefault(false)
                        if (!ok) nativeLeaseRegistry.release(shmFilePath)
                        ok
                    }
                    NativeMappingLeaseRegistry.AcquireResult.SHARED -> true
                    NativeMappingLeaseRegistry.AcquireResult.CONFLICT -> false
                }
            }
            if (!acquired) {
                neutralizeFallback()
                return false
            }
        } else {
            neutralizeFallback()
        }

        sharedPath = shmFilePath
        initialized = true
        return true
    }

    override fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean {
        if (!initialized || playerIndex !in 0 until XInputTransport.MAX_PLAYERS ||
            packet.size != XInputTransport.PACKET_SIZE_BYTES) return false
        val effective = if (connected) packet.copyOf() else neutralPacket()
        if (nativeAvailable) {
            val buffer = ByteBuffer.allocateDirect(XInputTransport.PACKET_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(effective).flip()
            return runCatching { nativeWriteSerializedPacket(playerIndex, connected, buffer) }.getOrDefault(false)
        }
        fallback[playerIndex] = effective
        return true
    }

    @Synchronized
    override fun destroy() {
        if (!initialized) return
        if (nativeAvailable) {
            val path = sharedPath
            if (path != null) synchronized(nativeLeaseLock) {
                when (nativeLeaseRegistry.release(path)) {
                    NativeMappingLeaseRegistry.ReleaseResult.LAST -> {
                        val ok = runCatching { nativeDestroySharedMemory() }.getOrDefault(false)
                        if (!ok) {
                            nativeLeaseRegistry.restore(path)
                            return
                        }
                    }
                    NativeMappingLeaseRegistry.ReleaseResult.RETAINED,
                    NativeMappingLeaseRegistry.ReleaseResult.NOT_HELD -> Unit
                }
            }
        } else {
            neutralizeFallback()
        }
        initialized = false
        sharedPath = null
    }

    override fun isNativeAvailable() = nativeAvailable
    override fun isInitialized() = initialized
    fun getFallbackSnapshot(playerIndex: Int): ByteArray? = fallback[playerIndex]?.copyOf()
    fun sharedMemoryPath(): String? = sharedPath

    /** Reads a stable native MO2X slot snapshot for diagnostics and protocol verification. */
    fun readSnapshot(playerIndex: Int): ByteArray? {
        if (!initialized || playerIndex !in 0 until XInputTransport.MAX_PLAYERS) return null
        if (!nativeAvailable) return getFallbackSnapshot(playerIndex)
        val path = sharedPath ?: return null
        val buffer = ByteBuffer.allocateDirect(XInputTransport.PACKET_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val ok = runCatching { nativeReadSnapshot(path, playerIndex, buffer) }.getOrDefault(false)
        if (!ok) return null
        return ByteArray(XInputTransport.PACKET_SIZE_BYTES).also { buffer.position(0); buffer.get(it) }
    }

    override fun readReaderHeartbeat(): Long? {
        if (!nativeAvailable || !initialized) return null
        val path = sharedPath ?: return null
        return runCatching { nativeReadReaderHeartbeat(path) }.getOrNull()?.takeIf { it >= 0L }
    }

    private fun neutralizeFallback() {
        for (i in 0 until XInputTransport.MAX_PLAYERS) fallback[i] = neutralPacket()
    }

    private fun neutralPacket() = ByteArray(XInputTransport.PACKET_SIZE_BYTES)
    private fun loadLibrary(): Boolean = try {
        System.loadLibrary("mo2_input_bridge")
        true
    } catch (_: LinkageError) { false } catch (_: SecurityException) { false }

    private external fun nativeInitSharedMemory(path: String): Boolean
    private external fun nativeWriteSerializedPacket(playerIndex: Int, connected: Boolean, packet: ByteBuffer): Boolean
    private external fun nativeDestroySharedMemory(): Boolean
    private external fun nativeReadSnapshot(path: String, playerIndex: Int, buffer: ByteBuffer): Boolean
    private external fun nativeReadReaderHeartbeat(path: String): Long

    companion object {
        private val nativeLeaseLock = Any()
        private val nativeLeaseRegistry = NativeMappingLeaseRegistry()
    }
}
