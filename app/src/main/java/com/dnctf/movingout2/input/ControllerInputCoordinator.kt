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
    private var active = true

    init {
        profile.validate()
    }

    @Synchronized
    fun resume() {
        active = true
    }

    @Synchronized
    fun updateTouchState(state: ControllerState): Boolean {
        if (!active) return false
        touchState = state
        return sendCombined()
    }

    @Synchronized
    fun updatePhysicalState(state: ControllerState): Boolean {
        if (!active) return false
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
    private fun sendCombined(): Boolean {
        if (!active) return false
        return bridge.sendCombined(0, profile.apply(touchState), profile.apply(physicalState), connected = true)
    }

    @Synchronized
    fun clearTouch(): Boolean {
        touchState = ControllerState()
        return sendCombined()
    }

    /** Pause/disconnect neutralizes state and ignores callbacks until resume(). */
    @Synchronized
    fun resetAndDisconnect(): Boolean {
        active = false
        touchState = ControllerState()
        physicalState = ControllerState()
        return bridge.disconnectPlayer(0)
    }

    fun destroy() = bridge.destroy()
}
