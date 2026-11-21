package com.dnctf.movingout2.core.logging

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded persistent import telemetry. */
class ImportDiagnosticJournal(private val diagnosticsDirectory: File, private val maxEntries: Int = 10) {
    init { require(maxEntries > 0) }
    private val journalFile = File(diagnosticsDirectory, "import-diagnostic-journal.json")
    private val errorsFile = File(diagnosticsDirectory, "last-import-errors.log")
    private val temporaryJournalFile = File(diagnosticsDirectory, "import-diagnostic-journal.json.tmp")
    private val maxErrorLogBytes = 256 * 1024

    @Synchronized
    fun checkpoint(type: String, archiveName: String?, archiveBytes: Long? = null, entryCount: Int? = null,
        extractedBytes: Long? = null, availableBytesBefore: Long? = null, availableBytesAfter: Long? = null,
        stage: String, activationStatus: String = "PENDING", rollbackStatus: String = "NOT_REQUIRED", error: Throwable? = null) {
        runCatching {
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
        temporaryJournalFile.writeText(bounded.toString(2))
        runCatching {
            Files.move(temporaryJournalFile.toPath(), journalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporaryJournalFile.toPath(), journalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (error != null) appendBoundedErrorLog("\n--- ${System.currentTimeMillis()} $type $stage ---\n${error.stackTraceToString()}\n")
        } // Diagnostic persistence is best-effort and must never abort import/runtime operations.
    }
    private fun appendBoundedErrorLog(text: String) {
        val boundedEntry = text.takeLast(maxErrorLogBytes)
        errorsFile.appendText(boundedEntry)
        if (errorsFile.length() > maxErrorLogBytes) {
            val tail = errorsFile.readText().takeLast(maxErrorLogBytes)
            errorsFile.writeText(tail)
        }
    }
    fun journalFile() = journalFile
    fun errorsFile() = errorsFile
}
