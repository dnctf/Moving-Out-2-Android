package com.dnctf.movingout2.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRepositoryInstallerTest {

    private fun release(tag: String, published: String, draft: Boolean = false, complete: Boolean = true): JSONObject {
        val assets = JSONArray()
        if (complete) {
            assets.put(JSONObject().put("name", "moving-out-2-runtime-arm64-v8a.zip"))
            assets.put(JSONObject().put("name", "moving-out-2-runtime-arm64-v8a.zip.sha256"))
        } else {
            assets.put(JSONObject().put("name", "moving-out-2-runtime-arm64-v8a.zip.sha256"))
        }
        return JSONObject()
            .put("tag_name", tag)
            .put("prerelease", true)
            .put("draft", draft)
            .put("published_at", published)
            .put("assets", assets)
    }

    @Test fun selectsNewestCompletePublishedRuntimePrerelease() {
        val releases = JSONArray()
            .put(JSONObject().put("tag_name", "v1.0.0").put("prerelease", false).put("draft", false).put("published_at", "2026-11-25T12:00:00Z"))
            .put(release("runtime-v0.1.0-12", "2026-11-25T12:01:00Z"))
            .put(release("runtime-v0.1.0-13", "2026-11-25T12:02:00Z"))
            .put(release("runtime-v0.1.0-14", "2026-11-25T12:03:00Z", draft = true))
            .put(release("runtime-v0.1.0-15", "2026-11-25T12:04:00Z", complete = false))
        assertEquals("runtime-v0.1.0-13", latestRuntimePrerelease(releases)?.optString("tag_name"))
    }

    @Test fun returnsNullWhenNoCompletePublishedRuntimePrereleaseExists() {
        val releases = JSONArray()
            .put(JSONObject().put("tag_name", "v1.0.0").put("prerelease", false).put("draft", false).put("published_at", "2026-11-25T12:00:00Z"))
            .put(release("runtime-v0.1.0-16", "2026-11-25T12:05:00Z", complete = false))
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
