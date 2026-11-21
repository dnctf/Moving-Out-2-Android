package com.dnctf.movingout2.runtime

import com.dnctf.movingout2.input.ControllerButton
import com.dnctf.movingout2.input.ControllerState
import com.dnctf.movingout2.input.RuntimeInputBridge
import com.dnctf.movingout2.input.VirtualControllerEngine
import com.dnctf.movingout2.input.XInputTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** JVM integration seam test: touch/gamepad state -> XInput packet -> launch environment path. */
class EndToEndRuntimeWiringTest {
    private class RecordingTransport : XInputTransport {
        var path: String? = null
        var initialized = false
        val connected = mutableMapOf<Int, Boolean>()
        val packets = mutableMapOf<Int, ByteArray>()
        override fun init(shmFilePath: String): Boolean {
            path = File(shmFilePath).absolutePath
            initialized = shmFilePath.isNotBlank()
            return initialized
        }
        override fun writePacket(playerIndex: Int, connected: Boolean, packet: ByteArray): Boolean {
            if (!initialized || playerIndex !in 0..3 || packet.size != PACKET_SIZE_BYTES) return false
            this.connected[playerIndex] = connected
            packets[playerIndex] = packet.copyOf()
            return true
        }
        override fun destroy() { initialized = false }
        override fun isNativeAvailable() = true
        override fun isInitialized() = initialized
    }

    @Test fun touchAndPhysicalStateShareTransportAndLauncherPathContract() {
        val files = Files.createTempDirectory("mo2-e2e-wiring").toFile()
        val launchConfig = RuntimeLaunchConfig(files)
        val sharedFile = launchConfig.prepareSharedMemoryParent()
        val transport = RecordingTransport()
        val bridge = RuntimeInputBridge(transport)
        assertTrue(bridge.initialize(sharedFile.absolutePath))
        assertEquals(sharedFile.canonicalPath, File(transport.path!!).canonicalPath)

        val engine = VirtualControllerEngine()
        assertTrue(engine.claimButton(pointerId = 12, button = ControllerButton.A))
        val touchState = engine.snapshot()
        val physicalState = ControllerState(buttons = setOf(ControllerButton.B), leftX = -0.5f)
        assertTrue(bridge.sendCombined(0, ControllerState(touchState.buttons), physicalState))
        val packet = transport.packets.getValue(0)
        val buttonMask = (packet[0].toInt() and 0xff) or ((packet[1].toInt() and 0xff) shl 8)
        assertEquals(0x3000, buttonMask) // A + B
        assertEquals(RuntimeInputBridge.axisShort(-0.5f), java.nio.ByteBuffer.wrap(packet, 4, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short)

        val wineEnv = WineLaunchCommand.environment(files, sharedFile, File(files, "lib"), 60)
        val hostPath = transport.path!!
        val expectedWinePath = WineLaunchCommand.toWineZPath(hostPath)
        assertEquals(expectedWinePath, wineEnv["MO2_XINPUT_SHM_PATH"])
        assertEquals("1", wineEnv["BOX64_DYNAREC"])
        assertEquals("d3d11,dxgi,xinput1_3,xinput1_4=n,b", wineEnv["WINEDLLOVERRIDES"])

        // End-of-session/crash cleanup must disconnect every slot before mapping destruction.
        for (player in 0 until XInputTransport.MAX_PLAYERS) assertTrue(bridge.disconnectPlayer(player))
        for (player in 0 until XInputTransport.MAX_PLAYERS) {
            assertFalse(transport.connected[player]!!)
            assertArrayEquals(ByteArray(XInputTransport.PACKET_SIZE_BYTES), transport.packets[player])
        }
        bridge.destroy()
        assertFalse(bridge.isInitialized())
        files.deleteRecursively()
    }
}
