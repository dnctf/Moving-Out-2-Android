package com.dnctf.movingout2.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/** Responsive touch overlay. Geometry is recomputed from current view size/orientation. */
class VirtualControllerView(context: Context, private val onState: (ControllerState) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val engine = VirtualControllerEngine()
    private val buttonRects = mutableMapOf<ControllerButton, RectF>()
    private val triggerRects = mutableMapOf<Boolean, RectF>()
    private var leftCenter = Pair(0f, 0f)
    private var rightCenter = Pair(0f, 0f)
    private var stickRadius = 1f
    private var scale = 1f
    private var opacity = .44f
    private var offsetX = 0f
    private var offsetY = 0f
    private var overlayVisible = true
    private var hapticsEnabled = false

    fun configure(scale: Float = this.scale, opacity: Float = this.opacity, offsetX: Float = this.offsetX, offsetY: Float = this.offsetY) {
        this.scale = scale.takeIf { it.isFinite() }?.coerceIn(.65f, 1.5f) ?: 1f
        this.opacity = opacity.takeIf { it.isFinite() }?.coerceIn(.15f, 1f) ?: .44f
        this.offsetX = offsetX.takeIf { it.isFinite() }?.coerceIn(-.15f, .15f) ?: 0f
        this.offsetY = offsetY.takeIf { it.isFinite() }?.coerceIn(-.15f, .15f) ?: 0f
        invalidate()
    }
    fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        if (!visible) emit(engine.releaseAll())
        invalidate()
    }
    fun isOverlayVisible() = overlayVisible
    fun setHapticsEnabled(enabled: Boolean) { hapticsEnabled = enabled }
    private fun haptic() { if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayVisible || width <= 0 || height <= 0) return
        calculateGeometry()
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        val unit = min(width, height) * .12f * scale
        drawDpad(canvas, alpha, unit)
        drawStick(canvas, leftCenter, false, alpha)
        drawStick(canvas, rightCenter, true, alpha)
        drawFaceButtons(canvas, alpha, unit)
        drawTopButtons(canvas, alpha, unit)
    }

    private fun calculateGeometry() {
        buttonRects.clear(); triggerRects.clear()
        val w = width.toFloat(); val h = height.toFloat(); val u = min(w, h) * .12f * scale
        val ox = w * offsetX; val oy = h * offsetY
        leftCenter = Pair(w * .16f + ox, h * .72f + oy)
        rightCenter = Pair(w * .58f + ox, h * .72f + oy)
        stickRadius = u * 1.35f
        val dpadCenter = Pair(w * .20f + ox, h * .40f + oy)
        val dSize = u * .50f; val dOffset = u * .52f
        buttonRects[ControllerButton.UP] = rect(dpadCenter.first, dpadCenter.second - dOffset, dSize)
        buttonRects[ControllerButton.DOWN] = rect(dpadCenter.first, dpadCenter.second + dOffset, dSize)
        buttonRects[ControllerButton.LEFT] = rect(dpadCenter.first - dOffset, dpadCenter.second, dSize)
        buttonRects[ControllerButton.RIGHT] = rect(dpadCenter.first + dOffset, dpadCenter.second, dSize)
        val faceCenter = Pair(w * .82f + ox, h * .55f + oy); val fr = u * .30f; val fOffset = u * .62f
        buttonRects[ControllerButton.Y] = rect(faceCenter.first, faceCenter.second - fOffset, fr)
        buttonRects[ControllerButton.A] = rect(faceCenter.first, faceCenter.second + fOffset, fr)
        buttonRects[ControllerButton.X] = rect(faceCenter.first - fOffset, faceCenter.second, fr)
        buttonRects[ControllerButton.B] = rect(faceCenter.first + fOffset, faceCenter.second, fr)
        buttonRects[ControllerButton.LB] = rect(w * .18f + ox, h * .10f + oy, u * .55f, u * .30f)
        buttonRects[ControllerButton.RB] = rect(w * .82f + ox, h * .10f + oy, u * .55f, u * .30f)
        buttonRects[ControllerButton.BACK] = rect(w * .45f + ox, h * .12f + oy, u * .50f, u * .22f)
        buttonRects[ControllerButton.START] = rect(w * .55f + ox, h * .12f + oy, u * .50f, u * .22f)
        triggerRects[false] = rect(w * .30f + ox, h * .10f + oy, u * .25f, u * .30f)
        triggerRects[true] = rect(w * .70f + ox, h * .10f + oy, u * .25f, u * .30f)
    }
    private fun rect(cx: Float, cy: Float, radius: Float): RectF = RectF(cx-radius, cy-radius, cx+radius, cy+radius)
    private fun rect(cx: Float, cy: Float, halfWidth: Float, halfHeight: Float): RectF = RectF(cx-halfWidth, cy-halfHeight, cx+halfWidth, cy+halfHeight)

    private fun drawDpad(canvas: Canvas, alpha: Int, unit: Float) {
        listOf(ControllerButton.UP to "↑", ControllerButton.DOWN to "↓", ControllerButton.LEFT to "←", ControllerButton.RIGHT to "→")
            .forEach { (button, label) -> drawButton(canvas, buttonRects.getValue(button), label, button in engine.snapshot().buttons, alpha, unit * .34f) }
    }
    private fun drawFaceButtons(canvas: Canvas, alpha: Int, unit: Float) {
        listOf(ControllerButton.Y to "Y", ControllerButton.X to "X", ControllerButton.B to "B", ControllerButton.A to "A")
            .forEach { (button, label) -> drawButton(canvas, buttonRects.getValue(button), label, button in engine.snapshot().buttons, alpha, unit * .42f) }
    }
    private fun drawTopButtons(canvas: Canvas, alpha: Int, unit: Float) {
        listOf(ControllerButton.LB to "LB", ControllerButton.RB to "RB", ControllerButton.BACK to "BACK", ControllerButton.START to "START")
            .forEach { (button, label) -> drawButton(canvas, buttonRects.getValue(button), label, button in engine.snapshot().buttons, alpha, unit * .25f) }
        triggerRects.forEach { (right, r) ->
            val active = if (right) engine.snapshot().rightTrigger > 0f else engine.snapshot().leftTrigger > 0f
            paint.color = if (active) 0xCCFFFFFF.toInt() else (alpha shl 24) or 0xFFFFFF
            canvas.drawRoundRect(r, r.height() * .28f, r.height() * .28f, paint)
            paint.color = if (active) 0xFF222222.toInt() else 0xCCFFFFFF.toInt()
            paint.textSize = unit * .22f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText(if (right) "RT" else "LT", r.centerX(), r.centerY()+paint.textSize*.34f, paint)
        }
    }
    private fun drawStick(canvas: Canvas, center: Pair<Float,Float>, right: Boolean, alpha: Int) {
        val r = RectF(center.first-stickRadius, center.second-stickRadius, center.first+stickRadius, center.second+stickRadius)
        paint.color = (alpha * .32f).toInt().coerceIn(0,255) shl 24 or 0xFFFFFF
        canvas.drawOval(r, paint)
        val s = engine.snapshot(); val x = if (right) s.rightX else s.leftX; val y = if (right) s.rightY else s.leftY
        val knobRadius = stickRadius * .28f
        val knob = RectF(center.first+x*stickRadius*.68f-knobRadius, center.second+y*stickRadius*.68f-knobRadius,
            center.first+x*stickRadius*.68f+knobRadius, center.second+y*stickRadius*.68f+knobRadius)
        paint.color = ((alpha * .7f).toInt().coerceIn(0,255) shl 24) or 0xFFFFFF; canvas.drawOval(knob, paint)
        paint.color = (alpha shl 24) or 0xFFFFFF; paint.textSize = knobRadius*.85f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (right) "R" else "L", center.first, center.second+stickRadius*1.45f, paint)
    }
    private fun drawButton(canvas: Canvas, r: RectF, label: String, down: Boolean, alpha: Int, textSize: Float) {
        paint.color = if (down) 0xCCFFFFFF.toInt() else (alpha shl 24) or 0xFFFFFF
        canvas.drawRoundRect(r, r.height()*.25f, r.height()*.25f, paint)
        paint.color = if (down) 0xFF222222.toInt() else (alpha shl 24) or 0xFFFFFF
        paint.textSize = textSize; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, r.centerX(), r.centerY()+paint.textSize*.34f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!overlayVisible) return false
        if (buttonRects.isEmpty()) calculateGeometry()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex; val id = event.getPointerId(index); val x = event.getX(index); val y = event.getY(index)
                val trigger = triggerRects.entries.firstOrNull { it.value.contains(x,y) }
                if (trigger != null) {
                    val right = trigger.key
                    if (engine.claimTrigger(id, right)) { engine.setTrigger(id, 1f, right); haptic() }
                } else {
                    val button = buttonRects.entries.firstOrNull { it.value.contains(x,y) }?.key
                    if (button != null) {
                        if (engine.claimButton(id, button)) haptic()
                    } else if (insideStick(x,y,leftCenter)) {
                        if (engine.claimStick(id, false)) { moveStick(id, x, y, false); haptic() }
                    } else if (insideStick(x,y,rightCenter)) {
                        if (engine.claimStick(id, true)) { moveStick(id, x, y, true); haptic() }
                    }
                }
                emit(); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (engine.ownsStick(id, false)) moveStick(id, event.getX(i), event.getY(i), false)
                    if (engine.ownsStick(id, true)) moveStick(id, event.getX(i), event.getY(i), true)
                }
                emit(); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                engine.releasePointer(event.getPointerId(event.actionIndex)); emit(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { engine.releaseAll(); emit(); invalidate() }
        }
        return true
    }
    private fun insideStick(x: Float, y: Float, center: Pair<Float,Float>): Boolean {
        val dx=x-center.first; val dy=y-center.second
        return sqrt(dx*dx+dy*dy)<=stickRadius*1.25f
    }
    private fun moveStick(id: Int, x: Float, y: Float, right: Boolean) {
        val center = if (right) rightCenter else leftCenter
        engine.moveStick(id, x-center.first, y-center.second, stickRadius, right)
    }
    private fun emit(state: VirtualControllerEngine.State = engine.snapshot()) {
        onState(ControllerState(state.buttons, state.leftX, state.leftY, state.rightX, state.rightY, state.leftTrigger, state.rightTrigger))
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) { engine.releaseAll(); emit(); invalidate() }
    }
    override fun onDetachedFromWindow() {
        engine.releaseAll(); emit()
        super.onDetachedFromWindow()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
