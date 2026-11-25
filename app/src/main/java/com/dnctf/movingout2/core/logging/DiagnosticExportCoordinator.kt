package com.dnctf.movingout2.core.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.dnctf.movingout2.installation.GameInstallationManager
import com.dnctf.movingout2.installation.InstallationRepository
import com.dnctf.movingout2.installation.InstallationValidator
import com.dnctf.movingout2.core.model.InstallationSource
import com.dnctf.movingout2.runtime.RuntimeDiagnostic
import com.dnctf.movingout2.runtime.RuntimeDiagnostics
import com.dnctf.movingout2.runtime.RuntimeBundleValidator
import com.dnctf.movingout2.runtime.AndroidExecCapabilityProbe
import com.dnctf.movingout2.runtime.RuntimeGraphicsComponents
import com.dnctf.movingout2.graphics.VulkanCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Collects complete, source-aware diagnostics off the UI thread. */
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
                .put("abi", JSONArray(d.abi)).put("ramBytes", d.ramBytes).put("availableRamBytes", d.availableRamBytes)
                .put("gpuVendorHint", d.gpuVendorHint).put("availableProcessors", d.availableProcessors)
                .put("lowMemory", d.lowMemory).put("vulkanFeatures", JSONArray(d.vulkanFeatures))
                .put("blockers", JSONArray(d.blockers))
            } ?: JSONObject().put("error", device.exceptionOrNull()?.let(::describeError))
            val vulkanObject = vulkan.getOrNull()?.let { v -> JSONObject()
                .put("available", v.available).put("apiVersion", v.apiVersion)
                .put("gpuVendor", v.gpuVendor).put("gpuName", v.gpuName).put("extensions", JSONArray(v.extensions))
                .put("nativeProbeStatus", v.nativeProbeStatus).put("driverVersionRaw", v.driverVersionRaw)
                .put("blockers", JSONArray(v.blockers))
            } ?: JSONObject().put("error", vulkan.exceptionOrNull()?.let(::describeError))
            val deviceJson = JSONObject().put("device", deviceObject).put("vulkan", vulkanObject)
                .put("android", JSONObject().put("sdkInt", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE).put("targetSdk", appContext.applicationInfo.targetSdkVersion)
                    .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())))

            val gameJson = collectGameInstallationReport()
            val runtimeRoot = File(appContext.filesDir, "native_runtime/bundle")
            val runtime = RuntimeBundleValidator().validate(runtimeRoot)
            val graphics = RuntimeGraphicsComponents.assess(runtimeRoot)
            val runtimeJson = JSONObject()
                .put("status", runtime.status.name)
                .put("manifestVersion", runtime.manifestVersion)
                .put("root", runtimeRoot.absolutePath)
                .put("rootExists", runtimeRoot.exists())
                .put("rootIsDirectory", runtimeRoot.isDirectory)
                .put("rootCanRead", runtimeRoot.canRead())
                .put("rootCanWrite", runtimeRoot.canWrite())
                .put("box64", runtime.box64?.absolutePath)
                .put("wine64", runtime.wine64?.absolutePath)
                .put("wineserver", runtime.wineserver?.absolutePath)
                .put("libraryDirectory", runtime.libraryDirectory?.absolutePath)
                .put("blockers", JSONArray(runtime.blockers))
                .put("graphics", JSONObject().put("dxvk", graphics.dxvk.toString())
                    .put("turnip", graphics.turnip.toString()).put("notes", JSONArray(graphics.notes)))
            val storage = runCatching {
                val stat = StatFs(appContext.filesDir.absolutePath)
                JSONObject().put("filesDir", appContext.filesDir.absolutePath)
                    .put("cacheDir", appContext.cacheDir.absolutePath)
                    .put("availableBytes", stat.availableBytes).put("totalBytes", stat.totalBytes)
                    .put("filesDirCanExecute", appContext.filesDir.canExecute())
            }.getOrElse { JSONObject().put("error", describeError(it)) }
            runtimeJson.put("storage", storage)

            val manifest = File(runtimeRoot, "runtime-manifest.json")
            if (manifest.isFile) runCatching { runtimeJson.put("manifest", JSONObject(manifest.readText())) }
                .onFailure { runtimeJson.put("manifestReadError", describeError(it)) }

            if (runtime.isReady && runtime.box64 != null) {
                val exec = runCatching { AndroidExecCapabilityProbe(appContext, diagnosticLog).probe(runtime.box64) }
                runtimeJson.put("execProbe", exec.getOrNull()?.let {
                    JSONObject().put("capability", it.capability.name).put("allowed", it.allowed)
                        .put("exitCode", it.exitCode).put("executable", it.executable?.absolutePath)
                        .put("detail", it.detail).put("stdout", it.stdout).put("stderr", it.stderr)
                } ?: JSONObject().put("error", exec.exceptionOrNull()?.let(::describeError)))
            } else {
                runtimeJson.put("execProbe", JSONObject().put("status", "not_run")
                    .put("reason", "Runtime bundle did not pass structural validation; Box64 was not executed."))
            }

            val execAllowed = runtimeJson.optJSONObject("execProbe")?.optBoolean("allowed", false) == true
            val gameFilesPresent = gameJson.optString("status") == "files_present" &&
                gameJson.optString("source") == "INTERNAL_IMPORTED"
            val launchState = com.dnctf.movingout2.runtime.RuntimeInstallationState(
                runtime.isReady, execAllowed, gameFilesPresent
            )
            runtimeJson.put("launchReadiness", launchState.launchReadiness)
            gameJson.put("launchReadiness", launchState.launchReadiness)

            val runtimeProcessLog = File(appContext.filesDir, "diagnostics/runtime-process.log")
            if (runtimeProcessLog.isFile) {
                runCatching { runtimeProcessLog.readText(Charsets.UTF_8).takeLast(MAX_SESSION_LOG_CHARS) }
                    .onSuccess { runtimeJson.put("runtimeProcessLog", JSONObject().put("path", runtimeProcessLog.absolutePath)
                        .put("bytes", runtimeProcessLog.length()).put("tail", it)) }
            } else {
                runtimeJson.put("runtimeProcessLog", JSONObject().put("status", "not_found")
                    .put("path", runtimeProcessLog.absolutePath))
            }

            val sessionLog = File(appContext.filesDir, "diagnostics/wine_session.log")
            if (sessionLog.isFile) {
                diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "wine-session",
                    "Persisted Wine session log found: ${sessionLog.absolutePath} (${sessionLog.length()} bytes)"))
                runCatching {
                    sessionLog.readText(Charsets.UTF_8).takeLast(MAX_SESSION_LOG_CHARS)
                }.onSuccess { runtimeJson.put("wineSessionLog", JSONObject().put("path", sessionLog.absolutePath)
                    .put("bytes", sessionLog.length()).put("tail", it)) }
                    .onFailure { diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR,
                        "wine-session", "Unable to read ${sessionLog.absolutePath}: ${describeError(it)}")) }
            } else {
                diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "wine-session",
                    "No persisted Wine session log exists at ${sessionLog.absolutePath}; Box64/Wine stdout and stderr were not captured by the session supervisor."))
                runtimeJson.put("wineSessionLog", JSONObject().put("status", "not_found")
                    .put("path", sessionLog.absolutePath)
                    .put("reason", "No WineSessionSupervisor log file was produced."))
            }

            diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "diagnostics",
                "Diagnostic ZIP export requested; runtimeStatus=${runtime.status}; runtimeRoot=${runtimeRoot.absolutePath}"))
            val exportLog = diagnosticLog.snapshot().toMutableList()
            if (sessionLog.isFile) {
                runCatching { sessionLog.readLines(Charsets.UTF_8).takeLast(1000) }
                    .onSuccess { lines -> lines.forEach { line ->
                        exportLog += RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "wine-session-log", line)
                    } }
                    .onFailure { error -> exportLog += RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR,
                        "wine-session-log", "Failed reading ${sessionLog.absolutePath}: ${describeError(error)}") }
            }
            val result = runCatching {
                CrashDiagnosticExporter(File(appContext.cacheDir, "diagnostics")).export(
                    deviceJson.toString(2), gameJson.toString(2), runtimeJson.toString(2), exportLog,
                    exitCode = null,
                    crashInfo = if (sessionLog.isFile) "Wine session log included separately in runtime-bundle-report.json"
                        else "No Wine session log found; runtime process execution/output is not evidenced by this export.",
                    importDiagnosticDirectory = File(appContext.filesDir, "diagnostics")
                )
            }.onFailure { error ->
                diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "diagnostics",
                    "Diagnostic ZIP export failed: ${describeError(error)}"))
            }
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }, "diagnostic-export").apply { isDaemon = true; start() }
    }

    private fun collectGameInstallationReport(): JSONObject {
        val installations = installationRepository.allInstallations()
        val savedUri = installationRepository.savedUri()
        val selected = installations.firstOrNull()
        if (selected == null && savedUri == null) {
            return JSONObject().put("status", "not_selected")
                .put("reason", "No persisted game installation is registered in InstallationRepository.")
                .put("registeredInstallationCount", installations.size)
        }
        val item = selected
        if (item != null && item.source == InstallationSource.INTERNAL_IMPORTED) {
            val root = File(item.location)
            return runCatching {
                val report = InstallationValidator().validate(root)
                JSONObject().put("status", if (report.status.name == "VALID") "files_present" else "incomplete")
                    .put("source", item.source.name).put("displayName", item.displayName)
                    .put("root", root.absolutePath).put("rootExists", root.exists())
                    .put("validationStatus", report.status.name)
                    .put("missing", JSONArray(report.missingFiles)).put("accessErrors", JSONArray(report.accessErrors))
                    .put("detectedArchitecture", report.detectedArchitecture)
                    .put("detectedUnityVersion", report.detectedUnityVersion)
                    .put("metadataVersion", report.metadataVersion)
                    .put("stages", JSONArray().apply { report.stages.forEach { stage ->
                        put(JSONObject().put("stage", stage.stage.name).put("passed", stage.passed)
                            .put("details", JSONArray(stage.details)))
                    } })
            }.getOrElse { JSONObject().put("status", "validation_error")
                .put("source", item.source.name).put("root", root.absolutePath).put("error", describeError(it)) }
        }
        val uri = if (item?.source == InstallationSource.SAF_DIRECTORY) Uri.parse(item.location) else savedUri
        return if (uri == null) JSONObject().put("status", "not_selected")
            .put("reason", "Registered installation has no supported location.")
            .put("registeredInstallationCount", installations.size)
        else runCatching {
            val report = GameInstallationManager(appContext).validate(uri)
            JSONObject().put("status", if (report.isComplete) "files_present" else "incomplete")
                .put("source", "SAF_DIRECTORY").put("uri", uri.toString())
                .put("missing", JSONArray(report.missing)).put("accessErrors", JSONArray(report.accessErrors))
                .put("stageSummary", JSONArray(report.stageSummary))
                .put("unityVersion", report.detectedUnityVersion).put("metadataVersion", report.metadataVersion)
                .put("architecture", report.architecture)
                .put("importErrors", JSONObject().apply { report.importErrors.forEach { (key, value) -> put(key, JSONArray(value)) } })
        }.getOrElse { JSONObject().put("status", "validation_error")
            .put("source", "SAF_DIRECTORY").put("uri", uri.toString()).put("error", describeError(it)) }
    }

    private fun describeError(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return "${error.javaClass.name}: ${error.message ?: "no message"}\n${writer}"
    }

    companion object {
        private const val MAX_SESSION_LOG_CHARS = 48_000
    }
}
