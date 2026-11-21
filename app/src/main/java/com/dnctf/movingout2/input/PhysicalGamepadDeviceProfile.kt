package com.dnctf.movingout2.input

import android.view.InputDevice
import android.view.MotionEvent

/** Device-specific axis layout resolved from advertised motion ranges (no value-based fallback). */
data class PhysicalGamepadAxisLayout(
    val leftX: Int = MotionEvent.AXIS_X,
    val leftY: Int = MotionEvent.AXIS_Y,
    val rightX: Int = MotionEvent.AXIS_RX,
    val rightY: Int = MotionEvent.AXIS_RY,
    val leftTrigger: Int = MotionEvent.AXIS_LTRIGGER,
    val rightTrigger: Int = MotionEvent.AXIS_RTRIGGER,
    val hatX: Int = MotionEvent.AXIS_HAT_X,
    val hatY: Int = MotionEvent.AXIS_HAT_Y
) {
    companion object {
        fun resolve(axes: Set<Int>): PhysicalGamepadAxisLayout = PhysicalGamepadAxisLayout(
            rightX = if (MotionEvent.AXIS_RX in axes) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z,
            rightY = if (MotionEvent.AXIS_RY in axes) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ,
            leftTrigger = if (MotionEvent.AXIS_LTRIGGER in axes) MotionEvent.AXIS_LTRIGGER else MotionEvent.AXIS_BRAKE,
            rightTrigger = if (MotionEvent.AXIS_RTRIGGER in axes) MotionEvent.AXIS_RTRIGGER else MotionEvent.AXIS_GAS
        )
        fun fromDevice(device: InputDevice): PhysicalGamepadDeviceProfile {
            val axes = device.motionRanges.map { it.axis }.toSet()
            return PhysicalGamepadDeviceProfile(device.id, device.name, device.descriptor, resolve(axes))
        }
    }
}

data class PhysicalGamepadDeviceProfile(
    val deviceId: Int,
    val name: String,
    val descriptor: String,
    val axes: PhysicalGamepadAxisLayout
)
