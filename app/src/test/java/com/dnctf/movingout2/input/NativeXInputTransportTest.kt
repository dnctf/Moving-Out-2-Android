package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File

class NativeXInputTransportTest {
    @Test fun fallbackInitDoesNotRequireNativeLibrary() {
        val transport = NativeXInputTransport(forceFallbackForTesting = true)
        assertTrue(transport.init(File(System.getProperty("java.io.tmpdir"), "mo2x-test.shm").absolutePath))
        assertTrue(transport.isInitialized())
        assertFalse(transport.isNativeAvailable())
        transport.destroy()
        assertFalse(transport.isInitialized())
    }

    @Test fun packetEncodingIsExactlyTwelveLittleEndianBytes() {
        val bytes = RuntimeInputBridge.encodeXInputPacket(0x1234.toShort(), 0x56, 0x78,
            0x1234, (-2).toShort(), 0x3456, (-4).toShort())
        assertEquals(12, bytes.size)
        assertArrayEquals(byteArrayOf(0x34,0x12,0x56,0x78,0x34,0x12,0xfe.toByte(),0xff.toByte(),0x56,0x34,0xfc.toByte(),0xff.toByte()), bytes)
    }

    @Test fun validatesPlayerSlotAndPacketSize() {
        val t = NativeXInputTransport(forceFallbackForTesting = true)
        assertTrue(t.init("/tmp/mo2x-test"))
        assertFalse(t.writePacket(-1, true, ByteArray(12)))
        assertFalse(t.writePacket(4, true, ByteArray(12)))
        assertFalse(t.writePacket(0, true, ByteArray(11)))
        assertTrue(t.writePacket(3, true, ByteArray(12)))
        t.destroy()
    }

    @Test fun destroyNeutralizesFallbackSlots() {
        val t = NativeXInputTransport(forceFallbackForTesting = true)
        assertTrue(t.init("/tmp/mo2x-test"))
        val packet = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putShort(0x1234).array().let { it + ByteArray(10) }
        assertTrue(t.writePacket(2, true, packet))
        assertArrayEquals(packet, t.getFallbackSnapshot(2))
        t.destroy()
        assertArrayEquals(ByteArray(12), t.getFallbackSnapshot(2))
        assertArrayEquals(ByteArray(12), t.getFallbackSnapshot(0))
    }
}
