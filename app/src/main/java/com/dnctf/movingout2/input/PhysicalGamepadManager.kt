package com.dnctf.movingout2.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Lifecycle-scoped gamepad manager. Owns one translator per Android device and clears
 * that device's state on disconnect/change. This does not inject events globally.
 */
class PhysicalGamepadManager(
    context: Context,
    private val onDeviceState: (Int, PhysicalGamepadDeviceProfile, ControllerState, Boolean) -> Unit = { _, _, _, _ -> },
    private val onAggregateState: (ControllerState) -> Unit
) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val translators = mutableMapOf<Int, GamepadTranslator>()
    private val stateStore = PhysicalGamepadStateStore()
    private val profiles = mutableMapOf<Int, PhysicalGamepadDeviceProfile>()
    private var registered = false

    @Synchronized fun register() {
        if (registered) return
        inputManager.registerInputDeviceListener(this, Handler(contextMainLooper()))
        registered = true
        InputDevice.getDeviceIds().forEach(::addDevice)
        emit()
    }

    @Synchronized fun unregister() {
        if (!registered) return
        inputManager.unregisterInputDeviceListener(this)
        registered = false
        profiles.toMap().forEach { (deviceId, profile) ->
            onDeviceState(deviceId, profile, ControllerState(), false)
        }
        translators.values.forEach { it.releaseAll() }
        translators.clear()
        stateStore.clear()
        profiles.clear()
        emit()
    }

    fun handleKey(event: KeyEvent): ControllerState? {
        val translator = translators[event.deviceId] ?: return null
        val next = translator.onKey(event) ?: return null
        stateStore.update(event.deviceId, next)
        emitDevice(event.deviceId, connected = true)
        return emit()
    }

    fun handleMotion(event: MotionEvent): ControllerState? {
        val translator = translators[event.deviceId] ?: return null
        val next = translator.onMotion(event) ?: return null
        stateStore.update(event.deviceId, next)
        emitDevice(event.deviceId, connected = true)
        return emit()
    }

    fun deviceDescriptions(): List<String> = profiles.values.map { "${it.name} (${it.descriptor})" }
    fun deviceProfiles(): List<PhysicalGamepadDeviceProfile> = profiles.values.toList()

    @Synchronized override fun onInputDeviceAdded(deviceId: Int) { addDevice(deviceId); emit() }
    @Synchronized override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (isGamepad(device)) {
            // A device may be reclassified as a gamepad after its initial add callback.
            // Rebind ensures a translator and state slot exist, then neutralizes stale state.
            rebindGamepadAfterDeviceChange(deviceId, translators, stateStore)
            device?.let { profiles[deviceId] = PhysicalGamepadAxisLayout.fromDevice(it) }
            emitDevice(deviceId, connected = true)
        } else removeDevice(deviceId)
        emit()
    }
    @Synchronized override fun onInputDeviceRemoved(deviceId: Int) { removeDevice(deviceId); emit() }

    private fun addDevice(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!isGamepad(device)) return
        translators.putIfAbsent(deviceId, GamepadTranslator())
        stateStore.register(deviceId)
        profiles[deviceId] = PhysicalGamepadAxisLayout.fromDevice(device)
        emitDevice(deviceId, connected = true)
    }
    private fun removeDevice(deviceId: Int) {
        profiles[deviceId]?.let { onDeviceState(deviceId, it, ControllerState(), false) }
        translators.remove(deviceId)?.releaseAll()
        stateStore.remove(deviceId)
        profiles.remove(deviceId)
    }
    private fun isGamepad(device: InputDevice?): Boolean =
        device != null && isGameControllerSource(device.sources)

    private fun emitDevice(deviceId: Int, connected: Boolean) {
        val profile = profiles[deviceId] ?: return
        val state = stateStore.stateForDevice(deviceId) ?: ControllerState()
        onDeviceState(deviceId, profile, state, connected)
    }

    @Synchronized private fun emit(): ControllerState {
        val merged = stateStore.aggregate()
        onAggregateState(merged)
        return merged
    }

    private fun contextMainLooper() = android.os.Looper.getMainLooper()
}

/** Reclassifies/rebinds a device after Android reports its source capabilities changed. */
internal fun rebindGamepadAfterDeviceChange(
    deviceId: Int,
    translators: MutableMap<Int, GamepadTranslator>,
    stateStore: PhysicalGamepadStateStore
): GamepadTranslator {
    val translator = translators.getOrPut(deviceId) { GamepadTranslator() }
    translator.releaseAll()
    stateStore.register(deviceId)
    stateStore.resetDevice(deviceId)
    return translator
}
