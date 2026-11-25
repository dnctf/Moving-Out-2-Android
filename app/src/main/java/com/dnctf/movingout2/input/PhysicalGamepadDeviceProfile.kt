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
        fun resolve(axes: Set<Int>): PhysicalGamepadAxisLayout {
            // Select a complete stick pair when available. Avoid mixing RX with RZ
            // (or Z with RY) on partially advertised/vendor-specific devices.
            val standardRight = MotionEvent.AXIS_RX in axes && MotionEvent.AXIS_RY in axes
            val legacyRight = MotionEvent.AXIS_Z in axes && MotionEvent.AXIS_RZ in axes
            val useStandard = standardRight || (!legacyRight &&
                (MotionEvent.AXIS_RX in axes || MotionEvent.AXIS_RY in axes))
            val rightX = if (useStandard) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
            val rightY = if (useStandard) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ

            // Trigger axes are independent controls, unlike a 2D stick pair. Resolve
            // each side independently so hybrid vendor layouts (LTRIGGER + GAS, etc.)
            // do not silently drop an advertised trigger.
            val leftTrigger = if (MotionEvent.AXIS_LTRIGGER in axes) {
                MotionEvent.AXIS_LTRIGGER
            } else {
                MotionEvent.AXIS_BRAKE
            }
            val rightTrigger = if (MotionEvent.AXIS_RTRIGGER in axes) {
                MotionEvent.AXIS_RTRIGGER
            } else {
                MotionEvent.AXIS_GAS
            }

            return PhysicalGamepadAxisLayout(
                rightX = rightX,
                rightY = rightY,
                leftTrigger = leftTrigger,
                rightTrigger = rightTrigger
            )
        }
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
