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
            KeyEvent.ACTION_CANCEL -> keyButtons.remove(button)
            else -> return null
        }
        return snapshot()
    }

    fun onMotion(event: MotionEvent): ControllerState? {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) == 0) return null
        val device = InputDevice.getDevice(event.deviceId)
        lx = event.getAxisValue(MotionEvent.AXIS_X)
        ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rightX = if (supports(device, MotionEvent.AXIS_RX)) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
        val rightY = if (supports(device, MotionEvent.AXIS_RY)) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ
        rx = event.getAxisValue(rightX)
        ry = event.getAxisValue(rightY)
        val left = PhysicalGamepadNormalizer.normalizeStick(lx, ly, deadzone)
        val right = PhysicalGamepadNormalizer.normalizeStick(rx, ry, deadzone)
        lx = left.x; ly = left.y; rx = right.x; ry = right.y
        val lAxis = if (supports(device, MotionEvent.AXIS_LTRIGGER)) MotionEvent.AXIS_LTRIGGER else MotionEvent.AXIS_BRAKE
        val rAxis = if (supports(device, MotionEvent.AXIS_RTRIGGER)) MotionEvent.AXIS_RTRIGGER else MotionEvent.AXIS_GAS
        lt = normalizeTrigger(event.getAxisValue(lAxis), device?.getMotionRange(lAxis))
        rt = normalizeTrigger(event.getAxisValue(rAxis), device?.getMotionRange(rAxis))
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

    private fun supports(device: InputDevice?, axis: Int): Boolean =
        device?.getMotionRange(axis) != null

    private fun normalizeTrigger(raw: Float, range: InputDevice.MotionRange?): Float {
        if (!raw.isFinite()) return 0f
        if (range != null && range.max > range.min) {
            val normalized = if (range.min < 0f) (raw - range.min) / (range.max - range.min)
                else raw / range.max
            return normalized.coerceIn(0f, 1f)
        }
        return PhysicalGamepadNormalizer.normalizeTrigger(raw)
    }

    private fun snapshot() = ControllerState(keyButtons + hatButtons, lx, ly, rx, ry, lt, rt)

}
