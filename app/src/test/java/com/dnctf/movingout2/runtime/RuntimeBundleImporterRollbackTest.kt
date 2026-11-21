package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RuntimeBundleImporterRollbackTest {
    @Test fun storagePreflightAcceptsExactReserve() {
        assertTrue(runtimeBundleStoragePreflight(512, 512))
    }

    @Test fun storagePreflightRejectsInsufficientReserve() {
        assertFalse(runtimeBundleStoragePreflight(511, 512))
    }

    @Test fun storagePreflightRejectsNegativeReserve() {
        assertFalse(runtimeBundleStoragePreflight(512, -1))
    }

    @Test fun rollbackRestoresBackupWhenTargetWasRemoved() {
        val root = Files.createTempDirectory("rollback-success").toFile()
        val staging = File(root, "staging").apply { mkdirs() }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "old.txt").writeText("previous") }
        val target = File(root, "target")
        rollbackImport(staging, backup, target, false, IOException("new import failed"))
        assertTrue(target.isDirectory)
        assertEquals("previous", File(target, "old.txt").readText())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }


    @Test fun recoveryKeepsValidActiveAndRemovesStaleBackup() {
        val root = Files.createTempDirectory("recovery-active").toFile()
        val target = File(root, "target").apply { mkdirs(); File(this, "marker").writeText("active") }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "marker").writeText("old") }
        recoverRuntimeBundle(target, backup) { File(it, "marker").isFile }
        assertEquals("active", File(target, "marker").readText())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }

    @Test fun recoveryRestoresValidBackupWhenActiveTargetIsCorrupt() {
        val root = Files.createTempDirectory("recovery-backup").toFile()
        val target = File(root, "target").apply { mkdirs(); File(this, "corrupt").writeText("new") }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "marker").writeText("previous-valid") }
        recoverRuntimeBundle(target, backup) { File(it, "marker").isFile }
        assertEquals("previous-valid", File(target, "marker").readText())
        assertFalse(backup.exists())
        root.deleteRecursively()
    }

    @Test fun recoveryPreservesBothDirectoriesWhenNeitherIsValid() {
        val root = Files.createTempDirectory("recovery-invalid").toFile()
        val target = File(root, "target").apply { mkdirs(); File(this, "corrupt").writeText("new") }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "corrupt").writeText("old") }
        val failure = runCatching { recoverRuntimeBundle(target, backup) { File(it, "marker").isFile } }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(target.exists())
        assertTrue(backup.exists())
        root.deleteRecursively()
    }

    @Test fun rollbackRestoreFailureThrowsExplicitlyAndLeavesBackup() {
        val root = Files.createTempDirectory("rollback-failure").toFile()
        val staging = File(root, "staging").apply { mkdirs() }
        val backup = File(root, "backup").apply { mkdirs(); File(this, "old.txt").writeText("previous") }
        val target = File(root, "target").apply { writeText("blocks rename") }
        val failure = runCatching { rollbackImport(staging, backup, target, false, IOException("original import failure")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("rollback could not restore"))
        assertTrue(backup.exists())
        root.deleteRecursively()
    }
}
