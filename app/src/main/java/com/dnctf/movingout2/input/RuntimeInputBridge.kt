package com.dnctf.movingout2.input

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class XInputPacket(
    val buttonsBitmask: Short = 0,
    val leftTrigger: Byte = 0,
    val rightTrigger: Byte = 0,
    val thumbLX: Short = 0,
    val thumbLY: Short = 0,
    val thumbRX: Short = 0,
    val thumbRY: Short = 0
) {
    fun serializeLittleEndian(): ByteArray = ByteBuffer.allocate(XInputTransport.PACKET_SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(buttonsBitmask).put(leftTrigger).put(rightTrigger)
        .putShort(thumbLX).putShort(thumbLY).putShort(thumbRX).putShort(thumbRY).array()
}

enum class InputBridgeStatus { WAITING_FOR_NATIVE_RUNTIME, BRIDGE_CONNECTED }

class RuntimeInputBridge(private val transport: XInputTransport = NativeXInputTransport()) {
    @Volatile var status = InputBridgeStatus.WAITING_FOR_NATIVE_RUNTIME
        private set

    fun initialize(shmFilePath: String): Boolean {
        val ok = transport.init(shmFilePath)
        updateStatus()
        return ok
    }
    fun setNativeRuntimeConnected(connected: Boolean) { updateStatus(connected) }

    fun packet(v: ControllerState, p: ControllerState = ControllerState(), merge: Boolean = true): XInputPacket {
        val s = if (merge) ControllerState(
            v.buttons + p.buttons, choose(v.leftX,p.leftX), choose(v.leftY,p.leftY),
            choose(v.rightX,p.rightX), choose(v.rightY,p.rightY),
            maxOf(v.leftTrigger,p.leftTrigger), maxOf(v.rightTrigger,p.rightTrigger)
        ) else v
        val map = mapOf(ControllerButton.UP to 0x0001, ControllerButton.DOWN to 0x0002,
            ControllerButton.LEFT to 0x0004, ControllerButton.RIGHT to 0x0008, ControllerButton.START to 0x0010,
            ControllerButton.BACK to 0x0020, ControllerButton.L3 to 0x0040, ControllerButton.R3 to 0x0080,
            ControllerButton.LB to 0x0100, ControllerButton.RB to 0x0200,
            ControllerButton.A to 0x1000, ControllerButton.B to 0x2000, ControllerButton.X to 0x4000, ControllerButton.Y to 0x8000)
        var bits = 0
        s.buttons.forEach { bits = bits or (map[it] ?: 0) }
        return XInputPacket(bits.toShort(), triggerByte(s.leftTrigger), triggerByte(s.rightTrigger),
            axisShort(s.leftX), axisShort(-s.leftY), axisShort(s.rightX), axisShort(-s.rightY))
    }

    fun send(playerIndex: Int, state: ControllerState, connected: Boolean = true): Boolean =
        transport.writePacket(playerIndex, connected, packet(state, merge = false).serializeLittleEndian())
    fun sendCombined(playerIndex: Int, virtualState: ControllerState, physicalState: ControllerState, connected: Boolean = true): Boolean =\n        transport.writePacket(playerIndex, connected, packet(virtualState, physicalState, merge = true).serializeLittleEndian())\n    fun disconnectPlayer(playerIndex: Int): Boolean = transport.writePacket(playerIndex, false, ByteArray(XInputTransport.PACKET_SIZE_BYTES))
    fun destroy() { transport.destroy(); status = InputBridgeStatus.WAITING_FOR_NATIVE_RUNTIME }
    fun isNativeAvailable() = transport.isNativeAvailable()
    fun isInitialized() = transport.isInitialized()

    private fun updateStatus(runtimeConnected: Boolean = false) {
        status = if (runtimeConnected && transport.isNativeAvailable() && transport.isInitialized())
            InputBridgeStatus.BRIDGE_CONNECTED else InputBridgeStatus.WAITING_FOR_NATIVE_RUNTIME
    }
    companion object {
        private fun choose(a: Float,b: Float) = if (a != 0f) a else b
        fun axisShort(v: Float): Short {
            val n = if (v.isFinite()) v.coerceIn(-1f,1f) else 0f
            return if (n <= -1f) (-32768).toShort() else (n * 32767f).toInt().coerceIn(-32767,32767).toShort()
        }
        fun triggerByte(v: Float) = ((if (v.isFinite()) v.coerceIn(0f,1f) else 0f) * 255f).toInt().toByte()
        fun encodeXInputPacket(buttons: Short, leftTrigger: Byte, rightTrigger: Byte,
            thumbLX: Short, thumbLY: Short, thumbRX: Short, thumbRY: Short): ByteArray =
            XInputPacket(buttons,leftTrigger,rightTrigger,thumbLX,thumbLY,thumbRX,thumbRY).serializeLittleEndian()
    }
}
