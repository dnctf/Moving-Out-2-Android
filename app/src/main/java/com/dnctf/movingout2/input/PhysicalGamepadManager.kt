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
        return emit()
    }

    fun handleMotion(event: MotionEvent): ControllerState? {
        val translator = translators[event.deviceId] ?: return null
        val next = translator.onMotion(event) ?: return null
        states[event.deviceId] = next
        return emit()
    }

    fun deviceDescriptions(): List<String> = profiles.values.map { "${it.name} (${it.descriptor})" }
    fun deviceProfiles(): List<PhysicalGamepadDeviceProfile> = profiles.values.toList()

    @Synchronized override fun onInputDeviceAdded(deviceId: Int) { addDevice(deviceId); emit() }
    @Synchronized override fun onInputDeviceChanged(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (isGamepad(device)) {
            translators[deviceId]?.releaseAll()
            stateStore.resetDevice(deviceId)
            device?.let { profiles[deviceId] = PhysicalGamepadAxisLayout.fromDevice(it) }
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
    }
    private fun removeDevice(deviceId: Int) {
        translators.remove(deviceId)?.releaseAll()
        stateStore.remove(deviceId)
        profiles.remove(deviceId)
    }
    private fun isGamepad(device: InputDevice?): Boolean = device != null &&
        (device.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0

    @Synchronized private fun emit(): ControllerState {
        val merged = stateStore.aggregate()
        onAggregateState(merged)
        return merged
    }

    private fun contextMainLooper() = android.os.Looper.getMainLooper()
}
