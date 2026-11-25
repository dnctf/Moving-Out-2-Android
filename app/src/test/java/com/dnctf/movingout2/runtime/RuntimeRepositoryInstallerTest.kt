package com.dnctf.movingout2.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRepositoryInstallerTest {
    @Test fun rejectsUnixAndWindowsTraversalPaths() {
        val slash = 0x5c.toChar()
        assertFalse(isSafeRuntimeArchiveEntry("../escape", false))
        assertFalse(isSafeRuntimeArchiveEntry("runtime/../escape", false))
        assertFalse(isSafeRuntimeArchiveEntry("runtime${slash}..${slash}escape", false))
        assertFalse(isSafeRuntimeArchiveEntry("${slash}${slash}server${slash}share", false))
        assertFalse(isSafeRuntimeArchiveEntry("C:${slash}Windows${slash}system.ini", false))
    }

    @Test fun rejectsEmptyDotAndControlSegments() {
        assertFalse(isSafeRuntimeArchiveEntry("", false))
        assertFalse(isSafeRuntimeArchiveEntry(".", false))
        assertFalse(isSafeRuntimeArchiveEntry("runtime/./box64", false))
        assertFalse(isSafeRuntimeArchiveEntry("runtime//box64", false))
        assertFalse(isSafeRuntimeArchiveEntry("runtime/${0x7f.toChar()}bad", false))
    }

    @Test fun acceptsRuntimeFilesAndDirectoryEntries() {
        assertTrue(isSafeRuntimeArchiveEntry("box64/bin/box64", false))
        assertTrue(isSafeRuntimeArchiveEntry("wine/lib/", true))
        assertTrue(isSafeRuntimeArchiveEntry("runtime${0x5c.toChar()}bin${0x5c.toChar()}wine64", false))
    }
}
