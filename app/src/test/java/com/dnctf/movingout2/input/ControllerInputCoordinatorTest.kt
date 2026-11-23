package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControllerInputCoordinatorTest {
    private class RecordingTransport : XInputTransport {
        var initialized = false
        var connected = false
        var packet = ByteArray(XInputTransport.PACKET_SIZE_BYTES)
        override fun init(shmFilePath: String): Boolean {
            initialized = shmFilePath.isNotBlank()
            return initialized
        }
        override fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean {
            if (!initialized || playerIndex !in 0 until XInputTransport.MAX_PLAYERS || packet.size != XInputTransport.PACKET_SIZE_BYTES) return false
            this.connected = connected
            this.packet = packet.copyOf()
            return true
        }
        override fun destroy() { initialized = false }
        override fun isNativeAvailable() = false
        override fun isInitialized() = initialized
    }

    private fun bridge(transport: RecordingTransport): RuntimeInputBridge =
        RuntimeInputBridge(transport).also { assertTrue(it.initialize("test-shared-memory")) }

    private fun buttonMask(packet: ByteArray): Int =
        (packet[0].toInt() and 0xff) or ((packet[1].toInt() and 0xff) shl 8)

    @Test
    fun touchAndPhysicalButtonsAreMergedInOnePacket() {
        val transport = RecordingTransport()
        val coordinator = ControllerInputCoordinator(bridge(transport))
        assertTrue(coordinator.updateTouchState(ControllerState(buttons = setOf(ControllerButton.A))))
        assertTrue(coordinator.updatePhysicalState(ControllerState(buttons = setOf(ControllerButton.B))))
        assertTrue(transport.connected)
        assertEquals(0x3000, buttonMask(transport.packet))
    }

    @Test
    fun profileMappingMatchesPacketPreviewAndLiveWrite() {
        val transport = RecordingTransport()
        val coordinator = ControllerInputCoordinator(bridge(transport))
        val touch = ControllerState(buttons = setOf(ControllerButton.A), leftX = 0.1f, rightX = 0.75f)
        assertTrue(coordinator.updateTouchState(touch))
        val profile = ControllerProfile(
            name = "Fixture remap",
            buttonMap = mapOf("A" to "X"),
            axisMap = mapOf("LEFT_X" to "RIGHT_X")
        )
        assertTrue(coordinator.setProfile(profile))
        val preview = coordinator.previewPacket(touch).serializeLittleEndian()
        assertArrayEquals("preview must represent the live mapped packet", transport.packet, preview)
        assertEquals(0x4000, buttonMask(transport.packet))
        val mappedLeftX = ByteBuffer.wrap(transport.packet, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short
        assertTrue(mappedLeftX.toInt() > 0)
    }

    @Test
    fun pauseResetDisconnectsAndWritesNeutralPacket() {
        val transport = RecordingTransport()
        val coordinator = ControllerInputCoordinator(bridge(transport))
        coordinator.updateTouchState(ControllerState(buttons = setOf(ControllerButton.A), leftX = 1f))
        coordinator.updatePhysicalState(ControllerState(buttons = setOf(ControllerButton.B), rightY = -1f))
        assertTrue(coordinator.resetAndDisconnect())
        assertFalse(transport.connected)
        assertArrayEquals(ByteArray(XInputTransport.PACKET_SIZE_BYTES), transport.packet)
        // Reuse after pause must reconnect without stale inputs.
        assertTrue(coordinator.updateTouchState(ControllerState(buttons = setOf(ControllerButton.X))))
        assertTrue(transport.connected)
        assertEquals(0x4000, buttonMask(transport.packet))
    }
}
