package com.dnctf.movingout2.input

/**
 * Owns merged touch/physical controller state and applies one profile consistently
 * to live XInput writes and controller-test packet previews. Android-free for JVM tests.
 */
class ControllerInputCoordinator(
    private val bridge: RuntimeInputBridge,
    initialProfile: ControllerProfile = ControllerProfile.presets.first()
) {
    private var touchState = ControllerState()
    private var physicalState = ControllerState()
    private var profile = initialProfile

    init {
        profile.validate()
    }

    @Synchronized
    fun updateTouchState(state: ControllerState): Boolean {
        touchState = state
        return sendCombined()
    }

    @Synchronized
    fun updatePhysicalState(state: ControllerState): Boolean {
        physicalState = state
        return sendCombined()
    }

    @Synchronized
    fun setProfile(next: ControllerProfile): Boolean {
        next.validate()
        profile = next
        return sendCombined()
    }

    /** Preview follows the same profile and merge order as the packet sent to the bridge. */
    @Synchronized
    fun previewPacket(touchOverride: ControllerState = touchState): XInputPacket =
        bridge.packet(profile.apply(touchOverride), profile.apply(physicalState))

    @Synchronized
    fun sendCombined(connected: Boolean = true): Boolean =
        bridge.sendCombined(0, profile.apply(touchState), profile.apply(physicalState), connected)

    @Synchronized
    fun clearTouch(): Boolean {
        touchState = ControllerState()
        return sendCombined()
    }

    /** Pause/disconnect neutralizes state and marks the player disconnected. */
    @Synchronized
    fun resetAndDisconnect(): Boolean {
        touchState = ControllerState()
        physicalState = ControllerState()
        return bridge.disconnectPlayer(0)
    }

    fun destroy() = bridge.destroy()
}
