package com.dnctf.movingout2.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Test fun extractsEmbeddedRuntimeArchiveOfflineWithManifestAtRoot() {
        val root = Files.createTempDirectory("offline-runtime-extract").toFile()
        try {
            val archive = File(root, "runtime.zip")
            val staging = File(root, "bundle_staging").apply { mkdirs() }
            writeZip(archive, listOf(
                "runtime-manifest.json" to """{"schemaVersion":2}""".toByteArray(),
                "box64/bin/box64" to byteArrayOf(1, 2, 3, 4)
            ))
            val counts = extractRuntimeArchiveSafely(
                zip = archive,
                destination = staging,
                maxEntries = 10,
                maxExpandedBytes = 1024,
                availableBytes = { 1024L * 1024L }
            )
            assertEquals(2, counts.first)
            assertEquals(23L, counts.second)
            assertEquals("""{"schemaVersion":2}""", File(staging, "runtime-manifest.json").readText())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(staging, "box64/bin/box64").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalEntryBeforeWritingOutsideStaging() {
        val root = Files.createTempDirectory("offline-runtime-traversal").toFile()
        try {
            val archive = File(root, "malicious.zip")
            val staging = File(root, "bundle_staging").apply { mkdirs() }
            writeZip(archive, listOf(
                "runtime-manifest.json" to "{}".toByteArray(),
                "../escaped.txt" to "no".toByteArray()
            ))
            val error = runCatching {
                extractRuntimeArchiveSafely(archive, staging, 10, 1024, availableBytes = { 1024L * 1024L })
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertFalse(File(root.parentFile, "escaped.txt").exists())
            assertFalse(File(root, "escaped.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun atomicActivationRestoresPreviousRuntimeWhenPostActivationValidationFails() {
        val root = Files.createTempDirectory("runtime-atomic-rollback").toFile()
        try {
            val target = File(root, "bundle").apply { mkdirs() }
            File(target, "version.txt").writeText("old")
            val staging = File(root, "bundle_staging").apply { mkdirs() }
            File(staging, "version.txt").writeText("new")
            val backup = File(root, "bundle_backup")
            val error = runCatching {
                activateRuntimeBundleAtomically(staging, target, backup) { false }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("old", File(target, "version.txt").readText())
            assertFalse(backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun atomicActivationReplacesRuntimeOnlyAfterSuccessfulValidation() {
        val root = Files.createTempDirectory("runtime-atomic-commit").toFile()
        try {
            val target = File(root, "bundle").apply { mkdirs() }
            File(target, "version.txt").writeText("old")
            val staging = File(root, "bundle_staging").apply { mkdirs() }
            File(staging, "version.txt").writeText("new")
            val backup = File(root, "bundle_backup")
            activateRuntimeBundleAtomically(staging, target, backup) { File(it, "version.txt").readText() == "new" }
            assertEquals("new", File(target, "version.txt").readText())
            assertFalse(backup.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun embeddedBundleReplacesMissingOrOlderVersionButNotCurrentRuntime() {
        assertFalse(shouldInstallBundledRuntime("0.1.2", null))
        assertFalse(shouldInstallBundledRuntime("0.1.2", "0.1.2"))
        assertTrue(shouldInstallBundledRuntime("0.1.1", "0.1.2"))
        assertTrue(shouldInstallBundledRuntime(null, "0.1.2"))
    }

}
