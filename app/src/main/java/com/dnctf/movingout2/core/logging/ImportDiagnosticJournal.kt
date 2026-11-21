package com.dnctf.movingout2.core.logging

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Bounded persistent import telemetry. */
class ImportDiagnosticJournal(private val diagnosticsDirectory: File, private val maxEntries: Int = 10) {
    init { require(maxEntries > 0) }
    private val journalFile = File(diagnosticsDirectory, "import-diagnostic-journal.json")
    private val errorsFile = File(diagnosticsDirectory, "last-import-errors.log")

    @Synchronized
    fun checkpoint(type: String, archiveName: String?, archiveBytes: Long? = null, entryCount: Int? = null,
        extractedBytes: Long? = null, availableBytesBefore: Long? = null, availableBytesAfter: Long? = null,
        stage: String, activationStatus: String = "PENDING", rollbackStatus: String = "NOT_REQUIRED", error: Throwable? = null) {
        if (!diagnosticsDirectory.exists() && !diagnosticsDirectory.mkdirs()) throw IOException("Unable to create diagnostic journal directory")
        val entries = runCatching { JSONArray(journalFile.takeIf { it.isFile }?.readText() ?: "[]") }.getOrDefault(JSONArray())
        val record = JSONObject().put("timestamp", System.currentTimeMillis()).put("type", type)
            .put("archiveName", archiveName ?: JSONObject.NULL).put("archiveBytes", archiveBytes ?: JSONObject.NULL)
            .put("entryCount", entryCount ?: JSONObject.NULL).put("extractedBytes", extractedBytes ?: JSONObject.NULL)
            .put("availableBytesBefore", availableBytesBefore ?: JSONObject.NULL).put("availableBytesAfter", availableBytesAfter ?: JSONObject.NULL)
            .put("stage", stage).put("activationStatus", activationStatus).put("rollbackStatus", rollbackStatus)
        if (error != null) record.put("error", error.toString()).put("stackTrace", error.stackTraceToString())
        entries.put(record)
        val bounded = JSONArray()
        for (i in maxOf(0, entries.length() - maxEntries) until entries.length()) bounded.put(entries.getJSONObject(i))
        journalFile.writeText(bounded.toString(2))
        if (error != null) errorsFile.appendText("\n--- ${System.currentTimeMillis()} $type $stage ---\n${error.stackTraceToString()}\n")
    }
    fun journalFile() = journalFile
    fun errorsFile() = errorsFile
}
