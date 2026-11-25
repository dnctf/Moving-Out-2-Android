package com.dnctf.movingout2.telemetry

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.TextView

class TelemetryOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    init {
        setTextColor(Color.GREEN)
        setBackgroundColor(Color.argb(160, 0, 0, 0))
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setPadding(16, 8, 16, 8)
        text = "FPS: -- | RAM: -- MB | Thermals: --"
    }

    fun update(snapshot: TelemetrySnapshot) {
        text = snapshot.toFormattedHud()
        val textColor = when (snapshot.thermalStatusCode) {
            in 0..1 -> Color.GREEN
            2 -> Color.YELLOW
            else -> Color.RED
        }
        setTextColor(textColor)
    }
}
