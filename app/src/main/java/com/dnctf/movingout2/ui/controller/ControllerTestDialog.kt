package com.dnctf.movingout2.ui.controller

import android.app.Activity
import android.app.Dialog
import android.widget.LinearLayout
import android.widget.TextView
import com.dnctf.movingout2.input.ControllerInputCoordinator
import com.dnctf.movingout2.input.ControllerProfile
import com.dnctf.movingout2.input.RuntimeInputBridge
import com.dnctf.movingout2.input.VirtualControllerView
import com.dnctf.movingout2.settings.LauncherSettings

/** Owns the controller diagnostics surface; Activity retains only the navigation callback. */
object ControllerTestDialog {
    fun show(
        activity: Activity,
        inputBridge: RuntimeInputBridge,
        inputCoordinator: ControllerInputCoordinator,
        controllerProfile: ControllerProfile
    ) {
        val dialog = Dialog(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 12)
        }
        val transportStatus = when {
            inputBridge.isNativeAvailable() && inputBridge.isInitialized() ->
                "native shared memory ready; Wine reader pending"
            inputBridge.isInitialized() ->
                "JVM fallback active; native shared memory unavailable"
            else -> "transport initialization failed"
        }
        val info = TextView(activity).apply {
            text = "Touch the controls or connect a controller.\nMO2X: $transportStatus"
            textSize = 14f
        }
        val stateText = TextView(activity).apply {
            text = "Input: idle"
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        val pad = VirtualControllerView(activity, controllerProfile.deadzone) { state ->
            inputCoordinator.updateTouchState(state)
            val readback = inputCoordinator.previewPacket(state)
                .serializeLittleEndian()
                .joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            stateText.text = "Input: buttons=${state.buttons.joinToString()} | " +
                "left=(${state.leftX}, ${state.leftY}) | right=(${state.rightX}, ${state.rightY})\n" +
                "MO2X packet: $readback"
        }
        val overlay = LauncherSettings.load(activity)
        pad.configure(
            overlay.controllerScale / 100f,
            overlay.controllerOpacity / 100f,
            (overlay.controllerOffsetX - 50) / 100f,
            (overlay.controllerOffsetY - 50) / 100f
        )
        pad.setHapticsEnabled(overlay.controllerHaptics)
        pad.setOverlayVisible(overlay.controllerEnabled)
        container.addView(info)
        container.addView(stateText)
        val padHeight = (activity.resources.displayMetrics.heightPixels * .52f).toInt().coerceIn(260, 520)
        container.addView(
            pad,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, padHeight)
        )
        dialog.setContentView(container)
        dialog.setTitle("Controller test")
        dialog.setOnDismissListener { inputCoordinator.clearTouch() }
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * .96f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
}
