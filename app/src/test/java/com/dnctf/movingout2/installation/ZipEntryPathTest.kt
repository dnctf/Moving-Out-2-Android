package com.dnctf.movingout2.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ZipEntryPathTest {
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
    private fun reject(value: String) {
        try { ZipEntryPath.normalize(value); fail("Expected unsafe path rejection: $value") }
        catch (e: java.io.IOException) { /* expected */ }
    }
}
