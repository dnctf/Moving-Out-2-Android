package com.dnctf.movingout2.graphics

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

data class VulkanCapabilities(
    val available: Boolean, val apiVersion: String? = null, val extensions: Set<String> = emptySet(),
    val gpuVendor: String? = null, val gpuName: String? = null, val blockers: List<String> = emptyList(),
    val hardwareLevelFeature: Boolean = false, val hardwareVersionFeature: Boolean = false
) {
    companion object {
        /** EGL/GLES renderer probe; Android feature flags are Vulkan capability hints, not a Vulkan API query. */
        fun probe(context: Context): VulkanCapabilities {
            val pm = context.packageManager
            val level = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            val version = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            val gl = probeEglRenderer()
            val renderer = gl?.second ?: System.getProperty("ro.hardware.egl") ?: System.getProperty("ro.hardware")
            val vendor = gl?.first
            val isAdreno = renderer.orEmpty().contains("Adreno", true) || vendor.orEmpty().contains("Qualcomm", true)
            val isMali = renderer.orEmpty().contains("Mali", true)
            val blockers = buildList {
                if (!level) add("Android Vulkan hardware-level feature is absent.")
                if (!version) add("Android Vulkan hardware-version feature is absent.")
                if (renderer.isNullOrBlank()) add("EGL renderer probe failed; GPU identity unavailable.")
                if (!isAdreno && !isMali) add("GPU is not identified as Adreno or Mali; driver readiness unknown.")
                add("Vulkan API version/extensions are not enumerated by this probe; Turnip status is unverified.")
            }
            return VulkanCapabilities(level && version, gpuVendor = vendor, gpuName = renderer,
                blockers = blockers, hardwareLevelFeature = level, hardwareVersionFeature = version)
        }

        private fun probeEglRenderer(): Pair<String?, String?>? = runCatching {
            val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return@runCatching null
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return@runCatching null
            val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] <= 0) {
                EGL14.eglTerminate(display); return@runCatching null
            }
            val config = configs[0] ?: run { EGL14.eglTerminate(display); return@runCatching null }
            val surface: EGLSurface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) { EGL14.eglTerminate(display); return@runCatching null }
            val context: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (context == EGL14.EGL_NO_CONTEXT || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                EGL14.eglDestroySurface(display, surface); EGL14.eglTerminate(display); return@runCatching null
            }
            val result = GLES20.glGetString(GLES20.GL_VENDOR)?.toString() to GLES20.glGetString(GLES20.GL_RENDERER)?.toString()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context); EGL14.eglDestroySurface(display, surface); EGL14.eglTerminate(display)
            result
        }.getOrNull()
    }
}
