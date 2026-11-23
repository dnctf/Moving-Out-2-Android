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

enum class InputBridgeStatus { BRIDGE_READY_WAITING_RUNTIME, BRIDGE_CONNECTED, BRIDGE_UNSUPPORTED }

data class RuntimeInputCapabilities(
    val nativeSharedMemoryAvailable: Boolean,
    val transportInitialized: Boolean,
    val runtimeConnectionConfirmed: Boolean,
    val status: InputBridgeStatus
)

class RuntimeInputBridge(private val transport: XInputTransport = NativeXInputTransport()) {
    @Volatile var status = InputBridgeStatus.BRIDGE_UNSUPPORTED
        private set
    val capabilities: RuntimeInputCapabilities get() = RuntimeInputCapabilities(
        nativeSharedMemoryAvailable = transport.isNativeAvailable(),
        transportInitialized = transport.isInitialized(),
        runtimeConnectionConfirmed = status == InputBridgeStatus.BRIDGE_CONNECTED,
        status = status
    )

    fun initialize(shmFilePath: String): Boolean {
        val initialized = transport.init(shmFilePath)
        updateStatus()
        return initialized
    }

    fun setNativeRuntimeConnected(connected: Boolean) = updateStatus(connected)

    fun packet(virtualState: ControllerState, physicalState: ControllerState = ControllerState(), merge: Boolean = true): XInputPacket {
        val state = if (merge) {
            val left = dominantStick(virtualState.leftX, virtualState.leftY, physicalState.leftX, physicalState.leftY)
            val right = dominantStick(virtualState.rightX, virtualState.rightY, physicalState.rightX, physicalState.rightY)
            ControllerState(
                virtualState.buttons + physicalState.buttons,
                left.first, left.second, right.first, right.second,
                maxTrigger(virtualState.leftTrigger, physicalState.leftTrigger),
                maxTrigger(virtualState.rightTrigger, physicalState.rightTrigger)
            )
        } else virtualState
        val buttonMap = mapOf(
            ControllerButton.UP to 0x0001, ControllerButton.DOWN to 0x0002,
            ControllerButton.LEFT to 0x0004, ControllerButton.RIGHT to 0x0008,
            ControllerButton.START to 0x0010, ControllerButton.BACK to 0x0020,
            ControllerButton.L3 to 0x0040, ControllerButton.R3 to 0x0080,
            ControllerButton.LB to 0x0100, ControllerButton.RB to 0x0200,
            ControllerButton.A to 0x1000, ControllerButton.B to 0x2000,
            ControllerButton.X to 0x4000, ControllerButton.Y to 0x8000
        )
        var bits = 0
        state.buttons.forEach { bits = bits or (buttonMap[it] ?: 0) }
        return XInputPacket(
            bits.toShort(),
            triggerByte(if (ControllerButton.LT in state.buttons) 1f else state.leftTrigger),
            triggerByte(if (ControllerButton.RT in state.buttons) 1f else state.rightTrigger),
            axisShort(state.leftX), axisShort(-state.leftY), axisShort(state.rightX), axisShort(-state.rightY)
        )
    }

    fun send(playerIndex: Int, state: ControllerState, connected: Boolean = true): Boolean =
        transport.writePacket(playerIndex, connected, packet(state, merge = false).serializeLittleEndian())

    fun sendCombined(playerIndex: Int, virtualState: ControllerState, physicalState: ControllerState, connected: Boolean = true): Boolean =
        transport.writePacket(playerIndex, connected, packet(virtualState, physicalState, merge = true).serializeLittleEndian())

    fun disconnectPlayer(playerIndex: Int): Boolean =
        transport.writePacket(playerIndex, false, ByteArray(XInputTransport.PACKET_SIZE_BYTES))

    /** Idempotent teardown: an already-destroyed bridge is neutralized successfully. */
    fun neutralizeAllPlayers(): Boolean {
        if (!transport.isInitialized()) return true
        var success = true
        for (player in 0 until XInputTransport.MAX_PLAYERS) {
            val disconnected = runCatching { disconnectPlayer(player) }.getOrDefault(false)
            success = disconnected && success
        }
        return success
    }

    fun destroy() {
        transport.destroy()
        status = InputBridgeStatus.BRIDGE_UNSUPPORTED
    }
    fun isNativeAvailable() = transport.isNativeAvailable()
    fun isInitialized() = transport.isInitialized()

    private fun updateStatus(runtimeConnected: Boolean = false) {
        status = when {
            runtimeConnected && transport.isNativeAvailable() && transport.isInitialized() -> InputBridgeStatus.BRIDGE_CONNECTED
            transport.isNativeAvailable() && transport.isInitialized() -> InputBridgeStatus.BRIDGE_READY_WAITING_RUNTIME
            else -> InputBridgeStatus.BRIDGE_UNSUPPORTED
        }
    }

    companion object {
        /**
         * Choose a whole stick vector, not independent axes. Touch wins equal-strength ties;
         * the stronger source wins when both sources move the same stick.
         */
        private fun dominantStick(vx: Float, vy: Float, px: Float, py: Float): Pair<Float, Float> {
            val virtual = safeAxis(vx) to safeAxis(vy)
            val physical = safeAxis(px) to safeAxis(py)
            val virtualMagnitude = virtual.first * virtual.first + virtual.second * virtual.second
            val physicalMagnitude = physical.first * physical.first + physical.second * physical.second
            return if (physicalMagnitude > virtualMagnitude) physical else virtual
        }
        private fun safeAxis(value: Float) = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        private fun safeTrigger(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        private fun maxTrigger(a: Float, b: Float) = maxOf(safeTrigger(a), safeTrigger(b))
        fun axisShort(value: Float): Short {
            val n = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
            return if (n <= -1f) (-32768).toShort() else (n * 32767f).toInt().coerceIn(-32767, 32767).toShort()
        }
        fun triggerByte(value: Float) = ((if (value.isFinite()) value.coerceIn(0f, 1f) else 0f) * 255f).toInt().toByte()
        fun encodeXInputPacket(buttons: Short, leftTrigger: Byte, rightTrigger: Byte,
            thumbLX: Short, thumbLY: Short, thumbRX: Short, thumbRY: Short): ByteArray =
            XInputPacket(buttons, leftTrigger, rightTrigger, thumbLX, thumbLY, thumbRX, thumbRY).serializeLittleEndian()
    }
}
