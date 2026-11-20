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
    val hardwareLevelFeature: Boolean = false, val hardwareVersionFeature: Boolean = false,
    val nativeProbeStatus: String? = null
) {
    companion object {
        /** Native Vulkan enumeration is authoritative; Android feature flags and EGL are fallback hints only. */
        fun probe(context: Context): VulkanCapabilities {
            val pm = context.packageManager
            val level = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            val versionFeature = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            val native = VulkanNativeProbe.parse(VulkanNativeProbe.runProbe())
            val nativeStatus = native["status"]
            val api = native["deviceApi"]
            val extensions = native["deviceExtensions"]?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()
            val rendererProbe = probeEglRenderer()
            val renderer = native["device"]?.takeIf(String::isNotBlank) ?: rendererProbe?.second
                ?: System.getProperty("ro.hardware.egl") ?: System.getProperty("ro.hardware")
            val vendor = native["vendorId"]?.let(::vendorName) ?: rendererProbe?.first
            val blockers = buildList {
                if (nativeStatus != "ok") add("Native Vulkan enumeration failed or unavailable (status=${nativeStatus ?: "native_probe_unavailable"}).")
                if (!level) add("Android Vulkan hardware-level feature flag is absent.")
                if (!versionFeature) add("Android Vulkan hardware-version feature flag is absent.")
                if (api.isNullOrBlank()) add("Vulkan physical-device API version is unavailable.")
                if (extensions.isEmpty()) add("Physical-device Vulkan extensions were not enumerated.")
                add("Turnip driver status is unverified; this probe reports the active Vulkan driver only.")
            }
            return VulkanCapabilities(nativeStatus == "ok", api, extensions, vendor, renderer, blockers, level, versionFeature, nativeStatus)
        }

        private fun vendorName(id: String): String = when (id.toLongOrNull()) {
            0x5143L -> "Qualcomm"
            0x13B5L -> "ARM"
            0x1002L -> "AMD"
            0x10DEL -> "NVIDIA"
            0x8086L -> "Intel"
            else -> "PCI vendor $id"
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
            val glContext: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (glContext == EGL14.EGL_NO_CONTEXT || !EGL14.eglMakeCurrent(display, surface, surface, glContext)) {
                EGL14.eglDestroySurface(display, surface); EGL14.eglTerminate(display); return@runCatching null
            }
            val result = GLES20.glGetString(GLES20.GL_VENDOR)?.toString() to GLES20.glGetString(GLES20.GL_RENDERER)?.toString()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, glContext); EGL14.eglDestroySurface(display, surface); EGL14.eglTerminate(display)
            result
        }.getOrNull()
    }
}
