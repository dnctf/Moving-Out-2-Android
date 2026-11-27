package com.dnctf.movingout2.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Per-device Android controller translator. Hat and key D-pad states are kept separate. */
class GamepadTranslator(private val deadzone: Float = 0f) {
    private val keyButtons = mutableSetOf<ControllerButton>()
    private var hatButtons = emptySet<ControllerButton>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f

    fun onKey(event: KeyEvent): ControllerState? {
        val button = GamepadKeyMapping.mapKeyCode(event.keyCode) ?: return null
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> applyButton(button, true)
            KeyEvent.ACTION_UP -> applyButton(button, false)
            else -> null
        }
    }

    fun onMotion(event: MotionEvent): ControllerState? {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) == 0) return null
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) return onMotionCancelled()

        val device = InputDevice.getDevice(event.deviceId)
        val left = PhysicalGamepadNormalizer.normalizeStick(
            axisValue(event, device, MotionEvent.AXIS_X), axisValue(event, device, MotionEvent.AXIS_Y), deadzone
        )
        val axisLayout = PhysicalGamepadAxisLayout.resolve(device?.motionRanges?.map { it.axis }?.toSet() ?: emptySet())
        val right = PhysicalGamepadNormalizer.normalizeStick(
            axisValue(event, device, axisLayout.rightX), axisValue(event, device, axisLayout.rightY), deadzone
        )
        val leftTrigger = normalizeTrigger(event.getAxisValue(axisLayout.leftTrigger), device?.getMotionRange(axisLayout.leftTrigger))
        val rightTrigger = normalizeTrigger(event.getAxisValue(axisLayout.rightTrigger), device?.getMotionRange(axisLayout.rightTrigger))
        val hat = PhysicalGamepadNormalizer.hatButtons(
            axisValue(event, device, MotionEvent.AXIS_HAT_X),
            axisValue(event, device, MotionEvent.AXIS_HAT_Y)
        )
        return updateNormalizedMotion(left, right, leftTrigger, rightTrigger, hat)
    }

    /** Shared key-state update seam, also usable by platform-neutral regression tests. */
    internal fun applyButton(button: ControllerButton, pressed: Boolean): ControllerState {
        if (pressed) keyButtons.add(button) else keyButtons.remove(button)
        return snapshot()
    }

    /** Commits one already-normalized physical sample without mixing device/event state. */
    internal fun updateNormalizedMotion(
        left: PhysicalGamepadNormalizer.Stick,
        right: PhysicalGamepadNormalizer.Stick,
        leftTrigger: Float,
        rightTrigger: Float,
        hat: Set<ControllerButton>
    ): ControllerState {
        lx = safeAxis(left.x); ly = safeAxis(left.y)
        rx = safeAxis(right.x); ry = safeAxis(right.y)
        lt = safeTrigger(leftTrigger); rt = safeTrigger(rightTrigger)
        hatButtons = hat.filter { it in DPAD_BUTTONS }.toSet()
        return snapshot()
    }

    /** ACTION_CANCEL invalidates the complete state for this physical device. */
    internal fun onMotionCancelled(): ControllerState = releaseAll()

    fun releaseAll(): ControllerState {
        keyButtons.clear(); hatButtons = emptySet()
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f
        return snapshot()
    }

    private fun axisValue(event: MotionEvent, device: InputDevice?, axis: Int): Float {
        val raw = event.getAxisValue(axis)
        val range = device?.getMotionRange(axis)
        return if (range != null) PhysicalGamepadNormalizer.normalizeAxisInRange(raw, range.min, range.max)
        else safeAxis(raw)
    }
    private fun normalizeTrigger(raw: Float, range: InputDevice.MotionRange?): Float =
        if (range != null) PhysicalGamepadNormalizer.normalizeTriggerInRange(raw, range.min, range.max)
        else PhysicalGamepadNormalizer.normalizeTrigger(raw)

    private fun snapshot() = ControllerState(keyButtons + hatButtons, lx, ly, rx, ry, lt, rt)

    private fun safeAxis(value: Float) = value.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    private fun safeTrigger(value: Float) = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    companion object {
        private val DPAD_BUTTONS = setOf(
            ControllerButton.UP, ControllerButton.DOWN, ControllerButton.LEFT, ControllerButton.RIGHT
        )
    }
}
