package com.dnctf.movingout2.installation

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SafGameImporterSafetyTest {
    @Test
    fun entryRegistryRejectsCaseInsensitiveAndUnicodeCollisions() {
        val registry = SafGameEntryNameRegistry()
        assertEquals("GameAssembly.dll", registry.register("GameAssembly.dll"))
        assertThrows(IOException::class.java) { registry.register("gameassembly.DLL") }

        val unicodeRegistry = SafGameEntryNameRegistry()
        unicodeRegistry.register("K")
        assertThrows(IOException::class.java) { unicodeRegistry.register("\u212A") }
    }

    @Test
    fun entryRegistryRejectsTraversalAndSeparatorNames() {
        val invalid = listOf("", ".", "..", "../escape", "folder/file", "folder\\file", "bad\u0000name")
        invalid.forEach { name ->
            assertThrows(IOException::class.java) { SafGameEntryNameRegistry().register(name) }
        }
    }

    @Test
    fun recoveryKeepsValidActiveAndRemovesStaleBackup() {
        val root = Files.createTempDirectory("saf-recovery-active").toFile()
        val target = File(root, "target").apply { mkdirs(); File(this, "valid.marker").writeText("active") }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "valid.marker").writeText("old") }

        recoverSafGameImport(target, backup) { File(it, "valid.marker").isFile }

        assertEquals("active", File(target, "valid.marker").readText())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }

    @Test
    fun recoveryRestoresValidBackupWhenTargetIsInvalid() {
        val root = Files.createTempDirectory("saf-recovery-backup").toFile()
        val target = File(root, "target").apply { mkdirs(); File(this, "corrupt.marker").writeText("new") }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "valid.marker").writeText("previous") }

        recoverSafGameImport(target, backup) { File(it, "valid.marker").isFile }

        assertEquals("previous", File(target, "valid.marker").readText())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }

    @Test
    fun recoveryPreservesBothTreesWhenNeitherIsValid() {
        val root = Files.createTempDirectory("saf-recovery-invalid").toFile()
        val target = File(root, "target").apply { mkdirs(); File(this, "corrupt.marker").writeText("new") }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "corrupt.marker").writeText("old") }

        val failure = runCatching {
            recoverSafGameImport(target, backup) { File(it, "valid.marker").isFile }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(target.exists())
        assertTrue(backup.exists())
        root.deleteRecursively()
    }

    @Test
    fun storagePreflightRequiresExpandedBytesPlusReserve() {
        assertEquals(1536L, safGameImportRequiredSpace(1024L, 512L))
        assertTrue(safGameImportStorageSatisfied(1536L, 1536L))
        assertFalse(safGameImportStorageSatisfied(1535L, 1536L))
        assertThrows(ArithmeticException::class.java) {
            safGameImportRequiredSpace(Long.MAX_VALUE, 1L)
        }
    }
}
