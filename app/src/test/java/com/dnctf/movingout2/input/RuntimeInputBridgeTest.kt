package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class RuntimeInputBridgeTest {
    private class FakeTransport : XInputTransport {
        var initialized = false
        var lastPlayer = -1
        var lastConnected = false
        var lastPacket = byteArrayOf()
        var disconnectFailures = mutableSetOf<Int>()
        override fun init(shmFilePath: String) = (shmFilePath.isNotBlank()).also { initialized = it }
        override fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean {
            lastPlayer = playerIndex; lastConnected = connected; lastPacket = packet.copyOf()
            return initialized && playerIndex !in disconnectFailures
        }
        override fun destroy() { initialized = false }
        override fun isNativeAvailable() = true
        override fun isInitialized() = initialized
    }

    @Test fun xInputSerializationIsLittleEndianAndFixedSize() {
        val bytes = RuntimeInputBridge.encodeXInputPacket(0x1234.toShort(), 0x56, 0x78,
            0x1234, (-32768).toShort(), 0x7fff, 0)
        assertEquals(12, bytes.size)
        assertArrayEquals(byteArrayOf(0x34,0x12,0x56,0x78,0x34,0x12,0x00,0x80.toByte(),0xff.toByte(),0x7f,0x00,0x00), bytes)
    }
    @Test fun signedAxesAndUnsignedTriggerConversionsCoverEndpoints() {
        assertEquals((-32768).toShort(), RuntimeInputBridge.axisShort(-1f))
        assertEquals(32767.toShort(), RuntimeInputBridge.axisShort(1f))
        assertEquals(0.toShort(), RuntimeInputBridge.axisShort(Float.NaN))
        assertEquals(0.toByte(), RuntimeInputBridge.triggerByte(0f))
        assertEquals(127.toByte(), RuntimeInputBridge.triggerByte(.5f))
        assertEquals(255.toByte(), RuntimeInputBridge.triggerByte(1f))
    }
    @Test fun mergedVirtualAndPhysicalStateUsesButtonUnionAndDominantStickVector() {
        val bridge = RuntimeInputBridge(FakeTransport())
        val v = ControllerState(buttons=setOf(ControllerButton.A), leftX=.25f, leftTrigger=.3f)
        val p = ControllerState(buttons=setOf(ControllerButton.B), leftX=-.8f, rightY=.5f, rightTrigger=.7f)
        val packet = bridge.packet(v,p)
        assertEquals(0x3000.toShort(), packet.buttonsBitmask)
        assertEquals(RuntimeInputBridge.axisShort(-.8f), packet.thumbLX)
        assertEquals(RuntimeInputBridge.axisShort(-.5f), packet.thumbRY)
        assertEquals(178.toByte(), packet.rightTrigger)
    }

    @Test fun dominantStickMergeNeverCombinesAxesFromDifferentSources() {
        val bridge = RuntimeInputBridge(FakeTransport())
        val touch = ControllerState(leftX=.35f, leftY=0f)
        val physical = ControllerState(leftX=0f, leftY=.9f)
        val packet = bridge.packet(touch, physical)
        assertEquals(0.toShort(), packet.thumbLX)
        assertEquals(RuntimeInputBridge.axisShort(-.9f), packet.thumbLY)
    }

    @Test fun equalMagnitudeStickMergePrefersVirtualSourceDeterministically() {
        val bridge = RuntimeInputBridge(FakeTransport())
        val touch = ControllerState(leftX=.6f, leftY=0f)
        val physical = ControllerState(leftX=0f, leftY=.6f)
        val packet = bridge.packet(touch, physical)
        assertEquals(RuntimeInputBridge.axisShort(.6f), packet.thumbLX)
        assertEquals(0.toShort(), packet.thumbLY)
    }

    @Test fun invalidMergedAxesAndTriggersAreNeutralizedBeforeSerialization() {
        val bridge = RuntimeInputBridge(FakeTransport())
        val touch = ControllerState(leftX=Float.NaN, leftY=Float.POSITIVE_INFINITY, leftTrigger=Float.NaN)
        val physical = ControllerState(leftX=.4f, leftY=0f, leftTrigger=.7f)
        val packet = bridge.packet(touch, physical)
        assertEquals(RuntimeInputBridge.axisShort(.4f), packet.thumbLX)
        assertEquals(0.toShort(), packet.thumbLY)
        assertEquals(178.toByte(), packet.leftTrigger)
    }
    @Test fun bridgeStatusNeverClaimsRuntimeConnectedWithoutTransport() {
        val fake = FakeTransport()
        val bridge = RuntimeInputBridge(fake)
        assertEquals(InputBridgeStatus.BRIDGE_UNSUPPORTED, bridge.status)
        assertTrue(bridge.initialize("/tmp/input.shm"))
        assertEquals(InputBridgeStatus.BRIDGE_READY_WAITING_RUNTIME, bridge.status)
        assertTrue(bridge.capabilities.nativeSharedMemoryAvailable)
        assertTrue(bridge.capabilities.transportInitialized)
        assertFalse(bridge.capabilities.runtimeConnectionConfirmed)
        bridge.setNativeRuntimeConnected(true)
        assertEquals(InputBridgeStatus.BRIDGE_CONNECTED, bridge.status)
        bridge.destroy()
        assertEquals(InputBridgeStatus.BRIDGE_UNSUPPORTED, bridge.status)
    }
    @Test fun neutralizationIsIdempotentAfterBridgeDestroy() {
        val fake = FakeTransport()
        val bridge = RuntimeInputBridge(fake)
        assertTrue(bridge.initialize("/tmp/input.shm"))
        assertTrue(bridge.neutralizeAllPlayers())
        bridge.destroy()
        assertTrue(bridge.neutralizeAllPlayers())
    }

    @Test fun neutralizationReportsPlayerDisconnectFailures() {
        val fake = FakeTransport()
        val bridge = RuntimeInputBridge(fake)
        assertTrue(bridge.initialize("/tmp/input.shm"))
        fake.disconnectFailures += 2
        assertFalse(bridge.neutralizeAllPlayers())
    }

    @Test fun disconnectWritesNeutralPacketAndMarksPlayerDisconnected() {
        val fake = FakeTransport()
        val bridge = RuntimeInputBridge(fake)
        bridge.initialize("/tmp/input.shm")
        assertTrue(bridge.disconnectPlayer(0))
        assertFalse(fake.lastConnected)
        assertArrayEquals(ByteArray(12), fake.lastPacket)
    }
}
