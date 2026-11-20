package com.dnctf.movingout2.core.logging

import com.dnctf.movingout2.runtime.RuntimeDiagnostic
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a portable, privacy-conscious ZIP diagnostic report from caller-supplied snapshots. */
class CrashDiagnosticExporter(private val outputDirectory: File) {
    fun export(
        deviceVulkanReportJson: String,
        gameInstallationReportJson: String,
        runtimeBundleReportJson: String,
        processDiagnostics: List<RuntimeDiagnostic>,
        exitCode: Int? = null,
        crashInfo: String? = null
    ): File {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) throw IllegalStateException("Cannot create diagnostic output directory")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(outputDirectory, "mo2-diagnostic-$stamp.zip")
        val log = buildString {
            processDiagnostics.takeLast(2000).forEach { entry ->
                append(Date(entry.timestampMillis)).append(" [").append(entry.severity).append("] ")
                    .append(entry.component).append(": ").append(entry.message).append('\n')
            }
            append("\nProcess exit code: ").append(exitCode?.toString() ?: "unknown").append('\n')
            if (!crashInfo.isNullOrBlank()) append("Crash info: ").append(crashInfo).append('\n')
        }
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("device-vulkan-report.json", deviceVulkanReportJson)
            put("game-installation-report.json", gameInstallationReportJson)
            put("runtime-bundle-report.json", runtimeBundleReportJson)
            put("runtime-process.log", log)
        }
        return target
    }
}