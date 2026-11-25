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
        val connectedByPlayer = mutableMapOf<Int, Boolean>()
        val packetByPlayer = mutableMapOf<Int, ByteArray>()
        override fun init(shmFilePath: String): Boolean {
            initialized = shmFilePath.isNotBlank()
            return initialized
        }
        override fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean {
            if (!initialized || playerIndex !in 0 until XInputTransport.MAX_PLAYERS || packet.size != XInputTransport.PACKET_SIZE_BYTES) return false
            this.connected = connected
            this.packet = packet.copyOf()
            connectedByPlayer[playerIndex] = connected
            packetByPlayer[playerIndex] = packet.copyOf()
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
    fun physicalDevicesReceiveDistinctXInputPlayersAndDisconnectIndependently() {
        val transport = RecordingTransport()
        val coordinator = ControllerInputCoordinator(bridge(transport))
        assertTrue(coordinator.updateTouchState(ControllerState(buttons = setOf(ControllerButton.A))))
        assertTrue(coordinator.updatePhysicalDevice(10, ControllerState(buttons = setOf(ControllerButton.B)), true))
        assertTrue(coordinator.updatePhysicalDevice(11, ControllerState(buttons = setOf(ControllerButton.X)), true))
        assertEquals(0x3000, buttonMask(transport.packetByPlayer.getValue(0)))
        assertEquals(0x4000, buttonMask(transport.packetByPlayer.getValue(1)))
        assertTrue(transport.connectedByPlayer.getValue(0))
        assertTrue(transport.connectedByPlayer.getValue(1))

        assertTrue(coordinator.updatePhysicalDevice(11, ControllerState(), false))
        assertFalse(transport.connectedByPlayer.getValue(1))
        assertArrayEquals(ByteArray(XInputTransport.PACKET_SIZE_BYTES), transport.packetByPlayer.getValue(1))

        // Releasing the player-0 physical controller must preserve the virtual player-0 input.
        assertTrue(coordinator.updatePhysicalDevice(10, ControllerState(), false))
        assertTrue(transport.connectedByPlayer.getValue(0))
        assertEquals(0x1000, buttonMask(transport.packetByPlayer.getValue(0)))
        coordinator.updateTouchState(ControllerState())
        assertEquals(0, buttonMask(transport.packetByPlayer.getValue(0)))
        assertTrue(transport.connectedByPlayer.getValue(0))

        assertTrue(coordinator.resetAndDisconnect())
        assertFalse(transport.connectedByPlayer.getValue(0))
        assertFalse(transport.connectedByPlayer.getValue(1))
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
    fun touchEngineDeadzoneIsNotAppliedAgainByTheProfile() {
        val transport = RecordingTransport()
        val profile = ControllerProfile(name = "Touch deadzone", deadzone = .12f)
        val coordinator = ControllerInputCoordinator(bridge(transport), profile)
        val engineSample = VirtualControllerEngine.radialDeadzone(.13f, 0f, profile.deadzone)
        assertTrue(engineSample.x > 0f)
        assertTrue(coordinator.updateTouchState(ControllerState(leftX = engineSample.x)))
        val actual = ByteBuffer.wrap(transport.packet, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short
        assertEquals(RuntimeInputBridge.axisShort(engineSample.x), actual)
    }

    @Test
    fun physicalRawAxisReceivesTheProfileDeadzoneExactlyOnce() {
        val transport = RecordingTransport()
        val profile = ControllerProfile(name = "Physical deadzone", deadzone = .12f)
        val coordinator = ControllerInputCoordinator(bridge(transport), profile)
        assertTrue(coordinator.updatePhysicalState(ControllerState(leftX = .2f)))
        val actual = ByteBuffer.wrap(transport.packet, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short
        val expected = PhysicalGamepadNormalizer.normalizeStick(.2f, 0f, profile.deadzone).x
        assertEquals(RuntimeInputBridge.axisShort(expected), actual)
    }

    @Test
    fun pauseIgnoresCallbacksUntilResumedAndPreventsStaleReconnect() {
        val transport = RecordingTransport()
        val coordinator = ControllerInputCoordinator(bridge(transport))
        coordinator.updateTouchState(ControllerState(buttons = setOf(ControllerButton.A), leftX = 1f))
        coordinator.updatePhysicalState(ControllerState(buttons = setOf(ControllerButton.B), rightY = -1f))
        assertTrue(coordinator.resetAndDisconnect())
        assertFalse(transport.connected)
        assertArrayEquals(ByteArray(XInputTransport.PACKET_SIZE_BYTES), transport.packet)

        // Late callbacks and dialog-dismiss cleanup must not reconnect while paused.
        assertFalse(coordinator.updateTouchState(ControllerState(buttons = setOf(ControllerButton.A))))
        assertFalse(coordinator.updatePhysicalState(ControllerState(buttons = setOf(ControllerButton.B))))
        assertFalse(coordinator.clearTouch())
        assertFalse(transport.connected)
        assertArrayEquals(ByteArray(XInputTransport.PACKET_SIZE_BYTES), transport.packet)

        coordinator.resume()
        assertTrue(coordinator.updateTouchState(ControllerState(buttons = setOf(ControllerButton.X))))
        assertTrue(transport.connected)
        assertEquals(0x4000, buttonMask(transport.packet))
    }
}
