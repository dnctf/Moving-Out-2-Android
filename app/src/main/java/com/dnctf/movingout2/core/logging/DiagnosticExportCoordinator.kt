package com.dnctf.movingout2.core.logging

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dnctf.movingout2.installation.GameInstallationManager
import com.dnctf.movingout2.installation.InstallationRepository
import com.dnctf.movingout2.runtime.RuntimeDiagnostic
import com.dnctf.movingout2.runtime.RuntimeDiagnostics
import com.dnctf.movingout2.runtime.RuntimeBundleValidator
import com.dnctf.movingout2.runtime.RuntimeGraphicsComponents
import com.dnctf.movingout2.graphics.VulkanCapabilities
import org.json.JSONObject
import java.io.File

/** Collects diagnostic snapshots off the UI thread and returns an export ZIP on the main thread. */
class DiagnosticExportCoordinator(
    context: Context,
    private val installationRepository: InstallationRepository,
    private val diagnosticLog: RuntimeDiagnostics
) {
    private val appContext = context.applicationContext

    fun exportAsync(onComplete: (Result<File>) -> Unit) {
        Thread({
            val device = runCatching { com.dnctf.movingout2.compatibility.DeviceDiagnosticsProvider.collect(appContext) }
            val vulkan = runCatching { VulkanCapabilities.probe(appContext) }
            val deviceObject = device.getOrNull()?.let { d -> JSONObject()
                .put("manufacturer", d.manufacturer).put("model", d.model).put("soc", d.soc)
                .put("abi", d.abi).put("ramBytes", d.ramBytes).put("availableRamBytes", d.availableRamBytes)
                .put("gpuVendorHint", d.gpuVendorHint).put("availableProcessors", d.availableProcessors)
                .put("lowMemory", d.lowMemory).put("vulkanFeatures", d.vulkanFeatures).put("blockers", d.blockers)
            } ?: JSONObject().put("error", device.exceptionOrNull()?.message)
            val vulkanObject = vulkan.getOrNull()?.let { v -> JSONObject()
                .put("available", v.available).put("apiVersion", v.apiVersion)
                .put("gpuVendor", v.gpuVendor).put("gpuName", v.gpuName).put("extensions", v.extensions)
                .put("nativeProbeStatus", v.nativeProbeStatus).put("blockers", v.blockers)
            } ?: JSONObject().put("error", vulkan.exceptionOrNull()?.message)
            val deviceJson = JSONObject().put("device", deviceObject).put("vulkan", vulkanObject)
            val gameJson = JSONObject().put("status", "not_selected")
            installationRepository.savedUri()?.let { uri ->
                runCatching { GameInstallationManager(appContext).validate(uri) }.onSuccess { report ->
                    gameJson.put("missing", report.missing).put("accessErrors", report.accessErrors)
                        .put("stageSummary", report.stageSummary).put("unityVersion", report.detectedUnityVersion)
                        .put("metadataVersion", report.metadataVersion).put("architecture", report.architecture)
                        .put("status", if (report.isComplete) "files_present" else "incomplete")
                }.onFailure { gameJson.put("error", it.message) }
            }
            val runtimeRoot = File(appContext.filesDir, "native_runtime/bundle")
            val runtime = RuntimeBundleValidator().validate(runtimeRoot)
            val runtimeJson = JSONObject().put("status", runtime.status.name).put("manifestVersion", runtime.manifestVersion)
                .put("root", runtime.root?.absolutePath).put("box64", runtime.box64?.absolutePath)
                .put("wine64", runtime.wine64?.absolutePath).put("wineserver", runtime.wineserver?.absolutePath)
                .put("libraryDirectory", runtime.libraryDirectory?.absolutePath).put("blockers", runtime.blockers)
            val manifest = File(runtimeRoot, "runtime-manifest.json")
            if (manifest.isFile) runCatching { runtimeJson.put("manifest", JSONObject(manifest.readText())) }
            diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "diagnostics", "Diagnostic ZIP export requested"))
            val result = runCatching {
                CrashDiagnosticExporter(File(appContext.cacheDir, "diagnostics")).export(
                    deviceJson.toString(2), gameJson.toString(2), runtimeJson.toString(2), diagnosticLog.snapshot(),
                    importDiagnosticDirectory = File(appContext.filesDir, "diagnostics")
                )
            }
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }, "diagnostic-export").apply { isDaemon = true; start() }
    }
}
