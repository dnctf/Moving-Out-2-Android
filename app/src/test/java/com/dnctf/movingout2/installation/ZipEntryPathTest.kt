package com.dnctf.movingout2.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ZipEntryPathTest {
    private fun completeGame(prefix: String = "") = listOf(
        "${prefix}mo2.exe",
        "${prefix}GameAssembly.dll",
        "${prefix}UnityPlayer.dll",
        "${prefix}baselib.dll",
        "${prefix}MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat"
    )

    @Test fun normalizesWindowsSeparators() {
        assertEquals("Game/Data/file.bin", ZipEntryPath.normalize("Game\\Data\\file.bin"))
    }
    @Test fun rejectsDuplicatesCaseInsensitively() {
        val registry = ZipEntryPathRegistry()
        registry.register("Game/File.dat")
        try { registry.register("game/file.dat"); fail("Expected duplicate rejection") }
        catch (e: java.io.IOException) { /* expected */ }
    }
    @Test fun rejectsTraversal() {
        reject("../escape.txt")
        reject("Game/../../escape.txt")
    }
    @Test fun rejectsAbsoluteAndDrivePaths() {
        reject("/root/file")
        reject("C:\\Windows\\file")
    }
    @Test fun rejectsEmptySegmentsAndDotSegments() {
        reject("Game//file")
        reject("./file")
    }
    @Test fun trimsDirectoryTrailingSlash() {
        assertEquals("Game/Folder", ZipEntryPath.normalize("Game/Folder/"))
    }
    @Test fun detectsGameAtArchiveRoot() {
        assertEquals("", GameZipRootLocator.findRootPrefix(completeGame()))
    }
    @Test fun detectsGameInsideMultiLevelWrapperFolders() {
        assertEquals("outer/wrapper/backup/", GameZipRootLocator.findRootPrefix(completeGame("outer/wrapper/backup/")))
    }
    @Test fun rootDetectionIsCaseInsensitive() {
        val entries = listOf("Outer/MO2.EXE", "Outer/GAMEASSEMBLY.DLL", "Outer/UnityPlayer.DLL",
            "Outer/Baselib.DLL", "Outer/MOVINGOUT2_DATA/IL2CPP_DATA/METADATA/GLOBAL-METADATA.DAT")
        assertEquals("Outer/", GameZipRootLocator.findRootPrefix(entries))
    }
    @Test fun incompleteArchiveCannotProduceGameRoot() {
        assertNull(GameZipRootLocator.findRootPrefix(completeGame().filterNot { it.endsWith("global-metadata.dat") }))
    }
    private fun reject(value: String) {
        try { ZipEntryPath.normalize(value); fail("Expected unsafe path rejection: $value") }
        catch (e: java.io.IOException) { /* expected */ }
    }
}
