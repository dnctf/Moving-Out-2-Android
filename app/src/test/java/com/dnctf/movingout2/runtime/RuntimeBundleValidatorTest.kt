package com.dnctf.movingout2.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class RuntimeBundleValidatorTest {
    private fun fakeArm64Elf(file: File) {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(64)
        bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
        bytes[4] = 2; bytes[5] = 1; bytes[18] = 183.toByte(); bytes[19] = 0
        file.writeBytes(bytes); file.setExecutable(true, false)
    }
    private fun bundle(): File {
        val root = Files.createTempDirectory("runtime-bundle-test-").toFile()
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=bin/box64\nwine64=wine/bin/wine64\nwineserver=wine/bin/wineserver\nlibrary_dir=lib\n")
        fakeArm64Elf(File(root, "bin/box64"))
        fakeArm64Elf(File(root, "wine/bin/wine64"))
        fakeArm64Elf(File(root, "wine/bin/wineserver"))
        File(root, "lib/libc.so").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        return root
    }
    private fun arm64Elf(machine: Int = 183): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        bytes[18] = machine.toByte()
        bytes[19] = (machine ushr 8).toByte()
        return bytes
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun directorySha256(directory: File): String {
        val listing = StringBuilder()
        directory.walkTopDown().filter { it.isFile }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .forEach { file ->
                listing.append(sha256(file))
                    .append("  ./")
                    .append(file.relativeTo(directory).invariantSeparatorsPath)
                    .append('\n')
            }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(listing.toString().toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun jsonManifestBundle(): File {
        val root = Files.createTempDirectory("runtime-json-bundle-").toFile()
        val components = JSONObject()

        fun addFile(key: String, relativePath: String, bytes: ByteArray, executable: Boolean = false) {
            val file = File(root, relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            if (executable) file.setExecutable(true, false)
            components.put(
                key,
                JSONObject()
                    .put("included", true)
                    .put("path", relativePath)
                    .put("sha256", sha256(file))
            )
        }

        addFile("box64", "box64/bin/box64", arm64Elf(), executable = true)
        addFile("wine64", "wine/bin/wine64", arm64Elf(), executable = true)
        addFile("wineserver", "wine/bin/wineserver", arm64Elf(), executable = true)
        addFile("d3d11", "dxvk/x64/d3d11.dll", byteArrayOf(1, 2, 3))
        addFile("dxgi", "dxvk/x64/dxgi.dll", byteArrayOf(4, 5, 6))

        val libraryDirectory = File(root, "wine/lib/wine").apply { mkdirs() }
        File(libraryDirectory, "module.so").writeBytes(byteArrayOf(7, 8, 9))
        components.put(
            "wineLib",
            JSONObject()
                .put("included", true)
                .put("path", "wine/lib/wine")
                .put("sha256", directorySha256(libraryDirectory))
        )
        components.put("turnip", JSONObject().put("included", false))

        File(root, "runtime-manifest.json").writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("targetAbi", "arm64-v8a")
                .put("components", components)
                .put("defaultLaunchProfile", JSONObject())
                .toString()
        )
        return root
    }

    @Test fun validatesCompleteArm64Bundle() {
        val root = bundle()
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.READY, report.status)
        assertTrue(report.isReady)
        root.deleteRecursively()
    }
    @Test fun validatesJsonManifestOnlyWithArm64ElfExecutables() {
        val root = jsonManifestBundle()
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.READY, report.status)
        assertTrue(report.blockers.toString(), report.isReady)
        root.deleteRecursively()
    }

    @Test fun rejectsWrongArchitectureInJsonManifestEvenWhenChecksumMatches() {
        val root = jsonManifestBundle()
        val box64 = File(root, "box64/bin/box64")
        box64.writeBytes(arm64Elf(machine = 0x3e)) // ELF64 little-endian x86_64
        box64.setExecutable(true, false)

        val manifestFile = File(root, "runtime-manifest.json")
        val manifest = JSONObject(manifestFile.readText())
        manifest.getJSONObject("components").getJSONObject("box64")
            .put("sha256", sha256(box64))
        manifestFile.writeText(manifest.toString())

        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(
            "Wrong-architecture Box64 must be blocked even with a matching checksum: ${report.blockers}",
            report.blockers.any { it.contains("box64") && it.contains("ELF AArch64") }
        )
        root.deleteRecursively()
    }

    @Test fun rejectsMissingManifest() {
        val root = Files.createTempDirectory("runtime-missing-").toFile()
        assertEquals(RuntimeBundleStatus.INVALID_MANIFEST, RuntimeBundleValidator().validate(root).status)
        root.deleteRecursively()
    }
    @Test fun rejectsWrongAbiManifest() {
        val root = bundle()
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=x86_64\nbox64=bin/box64\nwine64=wine/bin/wine64\nwineserver=wine/bin/wineserver\nlibrary_dir=lib\n")
        assertEquals(RuntimeBundleStatus.INVALID_MANIFEST, RuntimeBundleValidator().validate(root).status)
        root.deleteRecursively()
    }
    @Test fun rejectsInvalidExecutableHeader() {
        val root = bundle()
        File(root, "bin/box64").writeBytes(byteArrayOf(0, 1, 2))
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("ELF AArch64") })
        root.deleteRecursively()
    }
    @Test fun rejectsManifestPathTraversal() {
        val root = bundle()
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=../../escape\nwine64=wine/bin/wine64\nwineserver=wine/bin/wineserver\nlibrary_dir=lib\n")
        assertEquals(RuntimeBundleStatus.INCOMPLETE, RuntimeBundleValidator().validate(root).status)
        root.deleteRecursively()
    }
}
