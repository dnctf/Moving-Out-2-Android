package com.dnctf.movingout2.runtime

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WineSaveDataManagerTest {
    @Test fun saveDataRoundTripsWithoutChangingPayloadBytes() {
        val root = Files.createTempDirectory("wine-save-test").toFile()
        try {
            val source = File(root, "source/Studio/Game").apply { mkdirs() }
            val bytes = ByteArray(8192) { (it % 251).toByte() }
            File(source, "profile.dat").writeBytes(bytes)
            val archive = File(root, "saves.zip")
            assertEquals(bytes.size.toLong(), WineSaveDataManager.export(File(root, "source"), archive))
            val destination = File(root, "prefix/LocalLow")
            assertEquals(bytes.size.toLong(), WineSaveDataManager.importArchive(archive, destination))
            assertTrue(File(destination, "Studio/Game/profile.dat").readBytes().contentEquals(bytes))
        } finally { root.deleteRecursively() }
    }

    @Test fun saveImportRejectsZipSlipEntries() {
        val root = Files.createTempDirectory("wine-save-slip").toFile()
        try {
            val archive = File(root, "bad.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.txt")); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
            try { WineSaveDataManager.importArchive(archive, File(root, "LocalLow")); fail("Zip-slip path must be rejected") }
            catch (_: java.io.IOException) { }
            assertTrue(!File(root, "outside.txt").exists())
        } finally { root.deleteRecursively() }
    }
}
