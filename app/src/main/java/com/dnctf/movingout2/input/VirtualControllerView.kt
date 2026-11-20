package com.dnctf.movingout2.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Compact touch controller. Pointer IDs are tracked independently to avoid stuck inputs. */
class VirtualControllerView(context: Context, private val onState: (ControllerState) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerButtons = mutableMapOf<Int, ControllerButton>()
    private val pressed = mutableSetOf<ControllerButton>()
    private var stickPointer: Int? = null
    private var stickX = 0f
    private var stickY = 0f
    private val rects = mutableMapOf<ControllerButton, RectF>()
    private val idleAlpha = 112

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rects.clear()
        val unit = min(this.width, this.height) * 0.12f
        val dpadCenterX = this.width * 0.22f
        val dpadCenterY = this.height * 0.67f
        val d = unit * 0.55f
        val dpad = listOf(
            ControllerButton.UP to RectF(dpadCenterX-d,dpadCenterY-d*2,dpadCenterX+d,dpadCenterY),
            ControllerButton.DOWN to RectF(dpadCenterX-d,dpadCenterY,dpadCenterX+d,dpadCenterY+d*2),
            ControllerButton.LEFT to RectF(dpadCenterX-d*2,dpadCenterY-d,dpadCenterX,dpadCenterY+d),
            ControllerButton.RIGHT to RectF(dpadCenterX,dpadCenterY-d,dpadCenterX+d*2,dpadCenterY+d)
        )
        dpad.forEach { (button, rect) -> rects[button]=rect; drawButton(canvas,rect,button.name.take(1),button in pressed) }
        val stick = RectF(this.width*0.08f,this.height*0.73f,this.width*0.08f+unit*1.65f,this.height*0.73f+unit*1.65f)
        drawStick(canvas,stick)
        val cx=this.width*0.78f; val cy=this.height*0.69f; val r=unit*0.5f
        val face=listOf(ControllerButton.Y to RectF(cx-r,cy-r*2.2f,cx+r,cy-r*0.2f),ControllerButton.X to RectF(cx-r*2.1f,cy-r,cx-r*0.1f,cy+r),ControllerButton.B to RectF(cx+r*0.1f,cy-r,cx+r*2.1f,cy+r),ControllerButton.A to RectF(cx-r,cy+r*0.2f,cx+r,cy+r*2.2f))
        face.forEach { (b,rect) -> rects[b]=rect; drawButton(canvas,rect,b.name,b in pressed) }
        val centerY=this.height*0.88f
        listOf(ControllerButton.BACK to "BACK",ControllerButton.START to "START").forEachIndexed { i,(b,label) ->
            val rect=RectF(this.width*0.46f+i*unit*1.1f,centerY,this.width*0.46f+i*unit*1.1f+unit*0.85f,centerY+unit*0.45f)
            rects[b]=rect; drawButton(canvas,rect,label,b in pressed)
        }
    }
    private fun drawStick(canvas:Canvas,r:RectF) {
        paint.color=0x447FFFFFF.toInt();canvas.drawOval(r,paint)
        val knob=RectF(r.centerX()-r.width()*0.22f+stickX*r.width()*0.28f,r.centerY()-r.height()*0.22f+stickY*r.height()*0.28f,r.centerX()+r.width()*0.22f+stickX*r.width()*0.28f,r.centerY()+r.height()*0.22f+stickY*r.height()*0.28f)
        paint.color=0xAAFFFFFF.toInt();canvas.drawOval(knob,paint)
    }
    private fun drawButton(canvas:Canvas,r:RectF,label:String,down:Boolean) {
        paint.color=if(down)0xCCFFFFFF.toInt() else (idleAlpha shl 24) or 0xFFFFFF
        canvas.drawRoundRect(r,r.height()*0.24f,r.height()*0.24f,paint)
        paint.color=if(down)0xFF222222.toInt() else 0xCCFFFFFF.toInt()
        paint.textSize=r.height()*0.38f;paint.textAlign=Paint.Align.CENTER
        canvas.drawText(label,r.centerX(),r.centerY()+paint.textSize*0.34f,paint)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> {
                val index=event.actionIndex;val id=event.getPointerId(index);val x=event.getX(index);val y=event.getY(index)
                val button=rects.entries.firstOrNull{it.value.contains(x,y)}?.key
                if(button!=null){pointerButtons[id]=button;pressed.add(button)} else if(x<this.width*0.43f){stickPointer=id;updateStick(x,y)}
                emit();invalidate()
            }
            MotionEvent.ACTION_MOVE -> for(i in 0 until event.pointerCount){val id=event.getPointerId(i);if(stickPointer==id)updateStick(event.getX(i),event.getY(i))}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> {
                val id=event.getPointerId(event.actionIndex)
                pointerButtons.remove(id)?.let{button->if(pointerButtons.values.none{it==button})pressed.remove(button)}
                if(stickPointer==id){stickPointer=null;stickX=0f;stickY=0f}
                emit();invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {pointerButtons.clear();pressed.clear();stickPointer=null;stickX=0f;stickY=0f;emit();invalidate()}
        }
        if(event.actionMasked==MotionEvent.ACTION_MOVE){emit();invalidate()}
        return true
    }
    private fun updateStick(x:Float,y:Float) {
        val cx=this.width*0.16f;val cy=this.height*0.81f;val radius=this.width*0.11f
        stickX=((x-cx)/radius).coerceIn(-1f,1f);stickY=((y-cy)/radius).coerceIn(-1f,1f)
    }
    private fun emit(){onState(ControllerState(buttons=pressed.toSet(),leftX=stickX,leftY=stickY))}
}
