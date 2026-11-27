package com.dnctf.movingout2.graphics

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

interface SurfaceLifecycleListener {
    fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {}
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {}
    fun onSurfaceDestroyed() {}
}

class GameSurfaceHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    var lifecycleListener: SurfaceLifecycleListener? = null

    @Volatile var isSurfaceAvailable: Boolean = false
        private set
    @Volatile var surfaceWidth: Int = 0
        private set
    @Volatile var surfaceHeight: Int = 0
        private set

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        // SurfaceHolder may report creation before a non-zero buffer size is negotiated.
        // Do not advertise readiness or hand a zero-sized target to Vulkan/DXVK.
        if (surface != null && surface.isValid && surfaceWidth > 0 && surfaceHeight > 0) {
            isSurfaceAvailable = true
            lifecycleListener?.onSurfaceCreated(surface, surfaceWidth, surfaceHeight)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        val surface = holder.surface
        val ready = surface != null && surface.isValid && width > 0 && height > 0
        if (ready) {
            val wasAvailable = isSurfaceAvailable
            isSurfaceAvailable = true
            if (wasAvailable) lifecycleListener?.onSurfaceChanged(surface!!, width, height)
            else lifecycleListener?.onSurfaceCreated(surface!!, width, height)
        } else {
            isSurfaceAvailable = false
            lifecycleListener?.onSurfaceDestroyed()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Listener must detach its Vulkan surface/swapchain before Android releases it.
        isSurfaceAvailable = false
        lifecycleListener?.onSurfaceDestroyed()
        surfaceWidth = 0
        surfaceHeight = 0
    }

    fun probeCurrentSwapchain(): SwapchainProbeResult {
        val s = holder.surface
        if (!isSurfaceAvailable || s == null || !s.isValid) {
            return SwapchainProbeResult(
                status = "surface_unavailable",
                isReady = false,
                swapchainCreated = false,
                imageCount = 0,
                width = surfaceWidth,
                height = surfaceHeight,
                format = 0,
                acquirePassed = false,
                presentPassed = false,
                detail = "Host SurfaceView is not yet created, valid, or available"
            )
        }
        return VulkanNativeProbe.probeSwapchain(s)
    }
}
