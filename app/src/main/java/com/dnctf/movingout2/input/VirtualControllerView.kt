package com.dnctf.movingout2.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Lightweight touch controller with a clean, low-clutter layout and multi-touch support. */
class VirtualControllerView(context: Context, private val onState: (ControllerState) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePointers = mutableMapOf<Int, ControllerButton>()
    private val pressed = mutableSetOf<ControllerButton>()
    private val hitRects = mutableMapOf<ControllerButton, RectF>()
    private var stickPointer: Int? = null
    private var stickX = 0f; private var stickY = 0f
    private val alphaIdle = 115
    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitRects.clear()
        val s = min(width, height).toFloat()
        val size = s * 0.115f
        val leftBase = RectF(width*0.13f, height*0.73f, width*0.13f+size*1.5f, height*0.73f+size*1.5f)
        val rightBase = RectF(width*0.72f, height*0.68f, width*0.72f+size*2f, height*0.68f+size*2f)
        drawStick(canvas, leftBase, "STICK")
        val buttons = listOf(ControllerButton.Y to "Y", ControllerButton.X to "X", ControllerButton.B to "B", ControllerButton.A to "A")
        buttons.forEach { (b,label) ->
            val rect = when(b) {
                ControllerButton.Y -> RectF(rightBase.centerX(), rightBase.top-size*0.65f, rightBase.centerX()+size, rightBase.top+size*0.35f)
                ControllerButton.X -> RectF(rightBase.left-size*0.55f, rightBase.centerY()-size*0.5f, rightBase.left+size*0.45f, rightBase.centerY()+size*0.5f)
                ControllerButton.B -> RectF(rightBase.left+size*1.15f, rightBase.centerY()-size*0.5f, rightBase.left+size*2.15f, rightBase.centerY()+size*0.5f)
                else -> RectF(rightBase.centerX()-size*0.5f, rightBase.bottom-size*0.35f, rightBase.centerX()+size*0.5f, rightBase.bottom+size*0.65f)
            }
            hitRects[b]=rect; drawButton(canvas,rect,label,b in pressed)
        }
        val centerSize=size*0.75f
        listOf(ControllerButton.START to "START", ControllerButton.BACK to "BACK").forEachIndexed { i,(b,label) ->
            val rect=RectF(width*0.48f+i*(centerSize+size*0.2f),height*0.82f,width*0.48f+i*(centerSize+size*0.2f)+centerSize,height*0.82f+centerSize*0.45f)
            hitRects[b]=rect; drawButton(canvas,rect,label,b in pressed)
        }
        val d=size*0.58f
        val cx=width*0.27f; val cy=height*0.65f
        listOf(ControllerButton.UP to RectF(cx-d,cy-d*2,cx+d,cy), ControllerButton.DOWN to RectF(cx-d,cy,cx+d,cy+d*2), ControllerButton.LEFT to RectF(cx-d*2,cy-d,cx,cy+d), ControllerButton.RIGHT to RectF(cx,cy-d,cx+d*2,cy+d)).forEach { (b,r) -> hitRects[b]=r; drawButton(canvas,r,when(b){ControllerButton.UP->"↑";ControllerButton.DOWN->"↓";ControllerButton.LEFT->"←";else->"→"},b in pressed) }
    }
    private fun drawStick(canvas:Canvas,r:RectF,label:String) { paint.color=0x447FFFFFF.toInt(); canvas.drawOval(r,paint); val knob=RectF(r.centerX()-r.width*0.23f+stickX*r.width*0.25f,r.centerY()-r.height*0.23f+stickY*r.height*0.25f,r.centerX()+r.width*0.23f+stickX*r.width*0.25f,r.centerY()+r.height*0.23f+stickY*r.height*0.25f); paint.color=0xAAFFFFFF.toInt();canvas.drawOval(knob,paint);paint.color=0xBBFFFFFF.toInt();paint.textSize=r.width*0.16f;paint.textAlign=Paint.Align.CENTER;canvas.drawText(label,r.centerX(),r.bottom+r.height*0.18f,paint) }
    private fun drawButton(canvas:Canvas,r:RectF,label:String,down:Boolean){paint.color=if(down)0xCCFFFFFF.toInt() else (alphaIdle shl 24) or 0xFFFFFF;canvas.drawRoundRect(r,r.height*0.25f,r.height*0.25f,paint);paint.color=if(down)0xFF202020.toInt() else 0xCCFFFFFF.toInt();paint.textSize=r.height*0.45f;paint.textAlign=Paint.Align.CENTER;canvas.drawText(label,r.centerX(),r.centerY()+paint.textSize*0.35f,paint)}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> { val idx=event.actionIndex; val id=event.getPointerId(idx); val x=event.getX(idx);val y=event.getY(idx); val button=hitRects.entries.firstOrNull{it.value.contains(x,y)}?.key; if(button!=null){pressed.add(button);activePointers[id]=button}else if(x<width*0.45f){stickPointer=id;updateStick(x,y)}; emit(); invalidate() }
            MotionEvent.ACTION_MOVE -> { for(i in 0 until event.pointerCount){val id=event.getPointerId(i);if(activePointers.containsKey(id)){val b=activePointers[id]; val x=event.getX(i);val y=event.getY(i); if(hitRects[b]?.contains(x,y)!=true){pressed.remove(b!!);activePointers.remove(id)} } else if(stickPointer==id){updateStick(event.getX(i),event.getY(i))} };emit();invalidate() }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {val id=event.getPointerId(event.actionIndex);activePointers.remove(id)?.let{pressed.remove(it)};if(stickPointer==id){stickPointer=null;stickX=0f;stickY=0f};emit();invalidate() }
            MotionEvent.ACTION_CANCEL -> {activePointers.clear();pressed.clear();stickPointer=null;stickX=0f;stickY=0f;emit();invalidate() }
        }
        return true
    }
    private fun updateStick(x:Float,y:Float){stickX=((x-width*0.13f)/(width*0.13f)-1f).coerceIn(-1f,1f);stickY=((y-height*0.73f)/(height*0.13f)-1f).coerceIn(-1f,1f)}
    private fun emit(){onState(ControllerState(pressed.toSet(),stickX,stickY))}
}
