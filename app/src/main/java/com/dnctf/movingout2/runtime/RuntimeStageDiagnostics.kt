package com.dnctf.movingout2.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Machine-readable bootstrap milestones mirrored to logcat and bounded app-private storage. */
class RuntimeStageDiagnostics(context: Context) {
    private val directory = File(context.filesDir, "diagnostics")
    private val logFile = File(directory, "native-runtime-stages.log")
    private val maxBytes = 2L * 1024L * 1024L
    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    @Synchronized
    fun event(stage: String, event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) {
        val safeStage = stage.replace(Regex("[^A-Z0-9_]"), "_")
        val payload = buildString {
            append("stage=").append(safeStage).append(" event=").append(clean(event))
            fields.toSortedMap().forEach { (key, value) -> append(' ').append(clean(key)).append('=').append(clean(value?.toString() ?: "null")) }
            if (error != null) append(" error=").append(clean(error.javaClass.name + ":" + (error.message ?: "")))
        }
        Log.i(TAG, payload)
        runCatching {
            directory.mkdirs()
            if (logFile.exists() && logFile.length() >= maxBytes) {
                val previous = File(directory, "native-runtime.log.1")
                if (previous.exists()) previous.delete()
                if (!logFile.renameTo(previous)) logFile.delete()
            }
            FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8).use {
                it.append(timestamp.format(Date())).append(' ').append(payload).newLine()
                if (error != null) {
                    val trace = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
                    it.append(trace.take(12000))
                }
            }
        }.onFailure { Log.w(TAG, "diagnostic_file_write_failed: " + it.message) }
    }
    fun logFile(): File = logFile
    private fun clean(value: String): String = value.replace(Regex("[\\r\\n\\t]+"), "_").replace(" ", "_").take(512)
    companion object { const val TAG = "MO2_STAGE" }
}
