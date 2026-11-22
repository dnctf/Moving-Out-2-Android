package com.dnctf.movingout2.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Per-device Android controller translator. Hat and key D-pad states are kept separate. */
class GamepadTranslator(private val deadzone: Float = 0.18f) {
    private val keyButtons = mutableSetOf<ControllerButton>()
    private var hatButtons = emptySet<ControllerButton>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f

    fun onKey(event: KeyEvent): ControllerState? {
        val button = GamepadKeyMapping.mapKeyCode(event.keyCode) ?: return null
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyButtons.add(button)
            KeyEvent.ACTION_UP -> keyButtons.remove(button)
            else -> return null
        }
        return snapshot()
    }

    fun onMotion(event: MotionEvent): ControllerState? {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) == 0) return null
        val device = InputDevice.getDevice(event.deviceId)
        val left = PhysicalGamepadNormalizer.normalizeStick(
            axisValue(event, device, MotionEvent.AXIS_X), axisValue(event, device, MotionEvent.AXIS_Y), deadzone
        )
        val axisLayout = PhysicalGamepadAxisLayout.resolve(device?.motionRanges?.map { it.axis }?.toSet() ?: emptySet())
        val right = PhysicalGamepadNormalizer.normalizeStick(
            axisValue(event, device, axisLayout.rightX), axisValue(event, device, axisLayout.rightY), deadzone
        )
        lx = left.x; ly = left.y; rx = right.x; ry = right.y
        lt = normalizeTrigger(event.getAxisValue(axisLayout.leftTrigger), device?.getMotionRange(axisLayout.leftTrigger))
        rt = normalizeTrigger(event.getAxisValue(axisLayout.rightTrigger), device?.getMotionRange(axisLayout.rightTrigger))
        hatButtons = PhysicalGamepadNormalizer.hatButtons(
            event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        )
        return snapshot()
    }

    fun releaseAll(): ControllerState {
        keyButtons.clear(); hatButtons = emptySet()
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f
        return snapshot()
    }

    private fun axisValue(event: MotionEvent, device: InputDevice?, axis: Int): Float {
        val raw = event.getAxisValue(axis)
        val range = device?.getMotionRange(axis)
        return if (range != null) PhysicalGamepadNormalizer.normalizeAxisInRange(raw, range.min, range.max)
        else raw.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    }
    private fun normalizeTrigger(raw: Float, range: InputDevice.MotionRange?): Float {
        if (range != null) return PhysicalGamepadNormalizer.normalizeTriggerInRange(raw, range.min, range.max)
        return PhysicalGamepadNormalizer.normalizeTrigger(raw)
    }

    private fun snapshot() = ControllerState(keyButtons + hatButtons, lx, ly, rx, ry, lt, rt)

}
