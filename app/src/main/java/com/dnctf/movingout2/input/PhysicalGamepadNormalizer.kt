package com.dnctf.movingout2.input

import kotlin.math.abs
import kotlin.math.sqrt

/** Platform-neutral normalization for Android gamepad samples and disconnect safety. */
class PhysicalGamepadNormalizer(private val deadzone: Float = 0.12f) {
    data class Sample(
        val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
        val lt: Float = 0f, val rt: Float = 0f, val buttons: Set<ControllerButton> = emptySet()
    )
    private var state = Sample()
    fun update(leftX: Float, leftY: Float, rightX: Float, rightY: Float,
               leftTrigger: Float, rightTrigger: Float, buttons: Set<ControllerButton>): Sample {
        state = Sample(radial(leftX,leftY).x, radial(leftX,leftY).y, radial(rightX,rightY).x, radial(rightX,rightY).y,
            trigger(leftTrigger), trigger(rightTrigger), buttons.toSet())
        return state
    }
    fun reset(): Sample { state = Sample(); return state }
    fun snapshot() = state
    private fun trigger(v: Float) = if (v.isFinite()) v.coerceIn(0f,1f) else 0f
    private fun radial(x: Float,y: Float): Stick {
        if (!x.isFinite() || !y.isFinite()) return Stick(0f,0f)
        val mag=sqrt(x*x+y*y); if(mag<=deadzone) return Stick(0f,0f)
        val scale=((mag-deadzone)/(1f-deadzone).coerceAtLeast(0.001f)).coerceIn(0f,1f)
        val factor=if(mag>1f) 1f/mag else 1f
        return Stick((x*factor/mag*scale).coerceIn(-1f,1f),(y*factor/mag*scale).coerceIn(-1f,1f))
    }
    data class Stick(val x:Float,val y:Float)
    companion object {
        fun normalizeTrigger(raw: Float, digital: Boolean=false):Float = when {
            digital -> if(raw>0f) 1f else 0f
            !raw.isFinite() -> 0f
            raw < 0f -> ((raw+1f)/2f).coerceIn(0f,1f)
            else -> raw.coerceIn(0f,1f)
        }
        fun hatButtons(x:Float,y:Float):Set<ControllerButton> = buildSet {
            if(x < -0.5f) add(ControllerButton.LEFT); if(x > 0.5f) add(ControllerButton.RIGHT)
            if(y < -0.5f) add(ControllerButton.UP); if(y > 0.5f) add(ControllerButton.DOWN)
        }
    }
}
