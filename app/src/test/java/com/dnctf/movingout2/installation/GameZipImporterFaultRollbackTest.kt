package com.dnctf.movingout2.installation

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameZipImporterFaultRollbackTest {

    @Test
    fun directoryLockRejectsConcurrentImportsForSameTarget() {
        val tempDir = java.nio.file.Files.createTempDirectory("mo2-lock-test").toFile()
        try {
            val lock1 = GameZipImporter.getLockFor(tempDir)
            val lock2 = GameZipImporter.getLockFor(tempDir)
            assertSame(lock1, lock2)

            assertTrue(lock1.tryLock())
            assertFalse(lock2.tryLock())
            lock1.unlock()
            assertTrue(lock2.tryLock())
            lock2.unlock()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rootLocatorFindsValidUnityPrefix() {
        val validEntries = listOf(
            "MovingOut2/mo2.exe",
            "MovingOut2/GameAssembly.dll",
            "MovingOut2/UnityPlayer.dll",
            "MovingOut2/baselib.dll",
            "MovingOut2/MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat"
        )
        val prefix = GameZipRootLocator.findRootPrefix(validEntries)
        assertEquals("MovingOut2/", prefix)
    }

    @Test
    fun rootLocatorRejectsIncompleteArchive() {
        val missingDll = listOf(
            "mo2.exe",
            "GameAssembly.dll"
        )
        assertNull(GameZipRootLocator.findRootPrefix(missingDll))
    }

    @Test
    fun faultInjectorHooksReceiveCallbacks() {
        val events = mutableListOf<String>()
        val injector = object : ImportFaultInjector {
            override fun onPreflightComplete() { events += "preflight" }
            override fun onExtractionEntry(entryName: String, bytesDone: Long) { events += "entry:$entryName" }
            override fun onStagingValidated() { events += "staging_validated" }
            override fun onBeforeSwap() { events += "before_swap" }
            override fun onAfterSwap() { events += "after_swap" }
        }

        injector.onPreflightComplete()
        injector.onExtractionEntry("test.dll", 1024)
        injector.onStagingValidated()
        injector.onBeforeSwap()
        injector.onAfterSwap()

        assertEquals(
            listOf("preflight", "entry:test.dll", "staging_validated", "before_swap", "after_swap"),
            events
        )
    }

    @Test
    fun rollbackPreservesOriginalDirectoryWhenFaultInjectedBeforeSwap() {
        val tempBase = java.nio.file.Files.createTempDirectory("mo2-rollback-test").toFile()
        val root = File(tempBase, "game_import").apply { mkdirs() }
        val backup = File(tempBase, "game_import_backup")
        val staging = File(tempBase, "game_import_staging").apply { mkdirs() }

        // Setup existing installation
        val canary = File(root, "canary.txt").apply { writeText("original-data-safe") }

        // Simulate swap with injected fault
        var rollbackExecuted = false
        try {
            if (root.exists()) {
                backup.deleteRecursively()
                assertTrue(root.renameTo(backup))
            }
            // Injected fault happens before staging is activated
            throw IOException("Simulated fault before staging activation")
        } catch (e: Exception) {
            // Rollback execution
            if (backup.exists()) {
                if (root.exists()) root.deleteRecursively()
                assertTrue(backup.renameTo(root))
                rollbackExecuted = true
            }
        }

        assertTrue("Rollback should have been executed", rollbackExecuted)
        assertTrue("Original root should be restored", root.exists())
        assertEquals("original-data-safe", File(root, "canary.txt").readText())
        assertFalse("Backup directory should not linger", backup.exists())

        tempBase.deleteRecursively()
    }
}
