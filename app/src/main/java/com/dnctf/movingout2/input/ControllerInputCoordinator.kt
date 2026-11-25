package com.dnctf.movingout2.input

/**
 * Owns touch plus per-device physical input. The first physical device shares XInput player 0
 * with touch; additional devices receive independent XInput player slots 1..3.
 */
class ControllerInputCoordinator(
    private val bridge: RuntimeInputBridge,
    initialProfile: ControllerProfile = ControllerProfile.presets.first()
) {
    private var touchState = ControllerState()
    private var legacyPhysicalState = ControllerState()
    private val playerRouter = PhysicalGamepadPlayerRouter()
    private val physicalDevices = linkedMapOf<Int, ControllerState>()
    private var profile = initialProfile
    private var active = true

    init {
        profile.validate()
    }

    @Synchronized
    fun resume() {
        active = true
        sendCombined()
    }

    @Synchronized
    fun updateTouchState(state: ControllerState): Boolean {
        if (!active) return false
        touchState = state
        return sendCombined()
    }

    /** Legacy single-player seam retained for callers that provide an already-aggregated device. */
    @Synchronized
    fun updatePhysicalState(state: ControllerState): Boolean {
        if (!active) return false
        legacyPhysicalState = state
        return sendCombined()
    }

    /** Routes each connected physical device to its own XInput slot and releases it on disconnect. */
    @Synchronized
    fun updatePhysicalDevice(deviceId: Int, state: ControllerState, connected: Boolean): Boolean {
        if (!active) return false
        if (connected) {
            val player = playerRouter.connect(deviceId) ?: return false
            physicalDevices[deviceId] = state
            return if (player == 0) sendCombined() else bridge.send(player, profile.apply(state), connected = true)
        }

        val player = playerRouter.disconnect(deviceId) ?: return true
        physicalDevices.remove(deviceId)
        return if (player == 0) sendCombined() else bridge.disconnectPlayer(player)
    }

    @Synchronized
    fun setProfile(next: ControllerProfile): Boolean {
        next.validate()
        profile = next
        var success = sendCombined()
        physicalDevices.forEach { (deviceId, state) ->
            val player = playerRouter.playerIndex(deviceId)
            if (player != null && player > 0) {
                success = bridge.send(player, profile.apply(state), connected = true) && success
            }
        }
        return success
    }

    /** Preview follows the same player-0 profile and merge order as the live packet. */
    @Synchronized
    fun previewPacket(touchOverride: ControllerState = touchState): XInputPacket =
        bridge.packet(profile.apply(touchOverride, applyDeadzone = false), profile.apply(playerZeroPhysicalState()))

    @Synchronized
    private fun playerZeroPhysicalState(): ControllerState =
        physicalDevices.entries.firstOrNull { playerRouter.playerIndex(it.key) == 0 }?.value ?: legacyPhysicalState

    @Synchronized
    private fun sendCombined(): Boolean {
        if (!active) return false
        return bridge.sendCombined(
            0,
            profile.apply(touchState, applyDeadzone = false),
            profile.apply(playerZeroPhysicalState()),
            connected = true
        )
    }

    @Synchronized
    fun clearTouch(): Boolean {
        touchState = ControllerState()
        return sendCombined()
    }

    /** Pause/disconnect neutralizes all four XInput slots and ignores late callbacks until resume(). */
    @Synchronized
    fun resetAndDisconnect(): Boolean {
        active = false
        touchState = ControllerState()
        legacyPhysicalState = ControllerState()
        physicalDevices.clear()
        playerRouter.clear()
        return bridge.neutralizeAllPlayers()
    }

    fun destroy() = bridge.destroy()
}
