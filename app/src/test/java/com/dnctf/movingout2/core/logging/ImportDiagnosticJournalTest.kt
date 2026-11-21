package com.dnctf.movingout2.core.logging

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class ImportDiagnosticJournalTest {
    @Test fun journalPersistsStackTraceAndRotatesToTenEntries() {
        val root = Files.createTempDirectory("import-journal").toFile()
        val journal = ImportDiagnosticJournal(root)
        val error = IllegalStateException("extract failure")
        repeat(12) { i ->
            journal.checkpoint("GAME_ZIP", "archive-$i.zip", archiveBytes=123, entryCount=i,
                extractedBytes=45, availableBytesBefore=1000, availableBytesAfter=900,
                stage="FAILED", activationStatus="FAILED", rollbackStatus="ROLLBACK_COMPLETED", error=error)
        }
        val json = org.json.JSONArray(File(root, "import-diagnostic-journal.json").readText())
        assertEquals(10, json.length())
        assertEquals("archive-2.zip", json.getJSONObject(0).getString("archiveName"))
        assertTrue(json.getJSONObject(9).getString("stackTrace").contains("extract failure"))
        assertTrue(File(root, "last-import-errors.log").readText().contains("extract failure"))
    }

    @Test fun diagnosticExporterIncludesJournalAndErrorLogWhenPresent() {
        val root = Files.createTempDirectory("diagnostic-journal-export").toFile()
        File(root, "import-diagnostic-journal.json").writeText("""[{"stage":"failed"}]""")
        File(root, "last-import-errors.log").writeText("stack trace marker")
        val zip = CrashDiagnosticExporter(root).export("{}", "{}", "{}", emptyList(), importDiagnosticDirectory=root)
        ZipFile(zip).use { z ->
            assertEquals("""[{"stage":"failed"}]""", z.getInputStream(z.getEntry("import-diagnostic-journal.json")).bufferedReader().readText())
            assertEquals("stack trace marker", z.getInputStream(z.getEntry("last-import-errors.log")).bufferedReader().readText())
        }
        root.deleteRecursively()
    }

    @Test fun errorLogRemainsBoundedAfterRepeatedLargeErrors() {
        val root = Files.createTempDirectory("bounded-import-errors").toFile()
        val journal = ImportDiagnosticJournal(root)
        val largeError = IllegalStateException("x".repeat(40_000))
        repeat(12) {
            journal.checkpoint("RUNTIME_BUNDLE", "runtime", stage="FAILED", error=largeError)
        }
        val file = File(root, "last-import-errors.log")
        assertTrue(file.length() <= 256L * 1024L)
        assertTrue(file.readText().contains("RUNTIME_BUNDLE"))
        root.deleteRecursively()
    }

}

