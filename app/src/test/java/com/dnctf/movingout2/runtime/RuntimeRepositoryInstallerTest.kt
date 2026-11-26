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
            assets.put(JSONObject().put("name", "Moving-Out-2-Android-v0.2.0-alpha.3-arm64-v8a.apk"))
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

    @Test fun createsHttpRangeHeaderOnlyForNonZeroPartialLength() {
        assertEquals(null, runtimeRangeHeader(0L))
        assertEquals("bytes=1048576-", runtimeRangeHeader(1_048_576L))
    }

    @Test fun parsesContentRangeStartForResumedDownload() {
        assertEquals(1048576L, runtimeContentRangeStart("bytes 1048576-2097151/435419320"))
        assertEquals(0L, runtimeContentRangeStart("bytes 0-65535/435419320"))
        assertEquals(null, runtimeContentRangeStart("bytes */435419320"))
        assertEquals(null, runtimeContentRangeStart("invalid"))
    }

    @Test fun selectsNewestCompletePublishedRuntimePrerelease() {
        val releases = JSONArray()
            .put(JSONObject().put("tag_name", "v1.0.0").put("prerelease", false).put("draft", false).put("published_at", "2026-11-25T12:00:00Z"))
            .put(release("app-v0.2.0-alpha.1", "2026-11-25T12:01:00Z"))
            .put(release("app-v0.2.0-alpha.2", "2026-11-25T12:02:00Z"))
            .put(release("app-v0.2.0-alpha.3", "2026-11-25T12:03:00Z", draft = true))
            .put(release("app-v0.2.0-alpha.4", "2026-11-25T12:04:00Z", complete = false))
        assertEquals("app-v0.2.0-alpha.2", latestRuntimePrerelease(releases)?.optString("tag_name"))
    }

    @Test fun returnsNullWhenNoCompletePublishedRuntimePrereleaseExists() {
        val releases = JSONArray()
            .put(JSONObject().put("tag_name", "v1.0.0").put("prerelease", false).put("draft", false).put("published_at", "2026-11-25T12:00:00Z"))
            .put(release("app-v0.2.0-alpha.5", "2026-11-25T12:05:00Z", complete = false))
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

    @Test fun acceptsZipToolDotRootPrefixesButRejectsTraversalAfterPrefix() {
        assertEquals("", normalizeRuntimeArchiveEntry("./", true))
        assertEquals("runtime-manifest.json", normalizeRuntimeArchiveEntry("./runtime-manifest.json", false))
        assertEquals("box64/bin/box64", normalizeRuntimeArchiveEntry("./box64/bin/box64", false))
        assertFalse(isSafeRuntimeArchiveEntry("./../escape", false))
        assertFalse(isSafeRuntimeArchiveEntry("./box64/../../escape", false))
    }

    @Test fun acceptsRuntimeFilesAndDirectoryEntries() {
        assertTrue(isSafeRuntimeArchiveEntry("box64/bin/box64", false))
        assertTrue(isSafeRuntimeArchiveEntry("wine/lib/", true))
        assertTrue(isSafeRuntimeArchiveEntry("runtime${0x5c.toChar()}bin${0x5c.toChar()}wine64", false))
    }
    @Test fun embeddedBundleReplacesMissingOrOlderVersionButNotCurrentRuntime() {
        assertFalse(shouldInstallBundledRuntime("0.1.2", null))
        assertFalse(shouldInstallBundledRuntime("0.1.2", "0.1.2"))
        assertTrue(shouldInstallBundledRuntime("0.1.1", "0.1.2"))
        assertTrue(shouldInstallBundledRuntime(null, "0.1.2"))
    }

}
