package com.dnctf.movingout2.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRepositoryInstallerTest {

    @Test fun selectsNewestPublishedRuntimePrereleaseAndIgnoresStableOrDraftReleases() {
        val releases = JSONArray()
            .put(JSONObject().put("tag_name", "v1.0.0").put("prerelease", false).put("draft", false).put("published_at", "2026-11-25T12:00:00Z"))
            .put(JSONObject().put("tag_name", "runtime-v0.1.0-12").put("prerelease", true).put("draft", false).put("published_at", "2026-11-25T12:01:00Z"))
            .put(JSONObject().put("tag_name", "runtime-v0.1.0-13").put("prerelease", true).put("draft", false).put("published_at", "2026-11-25T12:02:00Z"))
            .put(JSONObject().put("tag_name", "runtime-v0.1.0-14").put("prerelease", true).put("draft", true).put("published_at", "2026-11-25T12:03:00Z"))
        assertEquals("runtime-v0.1.0-13", latestRuntimePrerelease(releases)?.optString("tag_name"))
    }

    @Test fun returnsNullWhenNoPublishedRuntimePrereleaseExists() {
        val releases = JSONArray()
            .put(JSONObject().put("tag_name", "v1.0.0").put("prerelease", false).put("draft", false).put("published_at", "2026-11-25T12:00:00Z"))
        assertEquals(null, latestRuntimePrerelease(releases))
    }
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
