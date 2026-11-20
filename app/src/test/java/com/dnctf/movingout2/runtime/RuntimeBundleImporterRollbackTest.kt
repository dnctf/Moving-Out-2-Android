package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RuntimeBundleImporterRollbackTest {
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
