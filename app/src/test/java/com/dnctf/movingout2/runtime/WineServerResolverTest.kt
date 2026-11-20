package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WineServerResolverTest {
    @Test fun resolvesExecutableFromLegacyManifest() {
        val root = Files.createTempDirectory("wine-resolver-").toFile()
        val server = File(root, "wine/bin/wineserver").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nwineserver=wine/bin/wineserver\n")
        assertEquals(server.canonicalFile, WineServerResolver.resolve(root))
        root.deleteRecursively()
    }

    @Test fun rejectsPathTraversalAndNonExecutableWineserver() {
        val root = Files.createTempDirectory("wine-resolver-safe-").toFile()
        val outside = File(root.parentFile, "outside-wineserver").apply { writeText("fake"); setExecutable(true) }
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nwineserver=../outside-wineserver\n")
        assertNull(WineServerResolver.resolve(root))
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nwineserver=inside\n")
        File(root, "inside").apply { writeText("not executable"); setExecutable(false) }
        assertNull(WineServerResolver.resolve(root))
        outside.delete()
        root.deleteRecursively()
    }

    @Test fun rejectsMissingOrEmptyManifestEntries() {
        val root = Files.createTempDirectory("wine-resolver-empty-").toFile()
        File(root, "runtime.properties").writeText("wineserver=empty\n")
        File(root, "empty").writeBytes(byteArrayOf())
        assertNull(WineServerResolver.resolve(root))
        root.deleteRecursively()
    }
}
