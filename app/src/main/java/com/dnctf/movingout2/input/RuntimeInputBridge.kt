package com.dnctf.movingout2.input
import java.nio.ByteBuffer
import java.nio.ByteOrder
data class XInputPacket(val buttonsBitmask:Short=0,val leftTrigger:Byte=0,val rightTrigger:Byte=0,val thumbLX:Short=0,val thumbLY:Short=0,val thumbRX:Short=0,val thumbRY:Short=0) {
 fun serializeLittleEndian()=ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort(buttonsBitmask).put(leftTrigger).put(rightTrigger).putShort(thumbLX).putShort(thumbLY).putShort(thumbRX).putShort(thumbRY).array()
}
enum class InputBridgeStatus { WAITING_FOR_NATIVE_RUNTIME, BRIDGE_CONNECTED }
class RuntimeInputBridge {
 var status=InputBridgeStatus.WAITING_FOR_NATIVE_RUNTIME; private set
 fun setNativeRuntimeConnected(connected:Boolean){status=if(connected) InputBridgeStatus.BRIDGE_CONNECTED else InputBridgeStatus.WAITING_FOR_NATIVE_RUNTIME}
 fun packet(v:ControllerState,p:ControllerState=ControllerState(),merge:Boolean=true):XInputPacket {
  val s=if(merge) ControllerState(v.buttons+p.buttons,choose(v.leftX,p.leftX),choose(v.leftY,p.leftY),choose(v.rightX,p.rightX),choose(v.rightY,p.rightY),maxOf(v.leftTrigger,p.leftTrigger),maxOf(v.rightTrigger,p.rightTrigger)) else v
  val map=mapOf(ControllerButton.UP to 1,ControllerButton.DOWN to 2,ControllerButton.LEFT to 4,ControllerButton.RIGHT to 8,ControllerButton.START to 16,ControllerButton.BACK to 32,ControllerButton.L3 to 64,ControllerButton.R3 to 128,ControllerButton.LB to 256,ControllerButton.RB to 512,ControllerButton.A to 4096,ControllerButton.B to 8192,ControllerButton.X to 16384,ControllerButton.Y to 32768)
  var bits=0;s.buttons.forEach{bits=bits or (map[it]?:0)}
  return XInputPacket(bits.toShort(),triggerByte(s.leftTrigger),triggerByte(s.rightTrigger),axisShort(s.leftX),axisShort(-s.leftY),axisShort(s.rightX),axisShort(-s.rightY))
 }
 companion object {
  private fun choose(a:Float,b:Float)=if(a!=0f)a else b
  fun axisShort(v:Float):Short {val n=if(v.isFinite())v.coerceIn(-1f,1f) else 0f;return if(n<=-1f)(-32768).toShort() else (n*32767f).toInt().coerceIn(-32767,32767).toShort()}
  fun triggerByte(v:Float)=( (if(v.isFinite())v.coerceIn(0f,1f) else 0f)*255f).toInt().toByte()
 }
}