package com.dnctf.movingout2.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class RuntimeBundleValidatorTest {
    private fun fakeArm64Elf(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(arm64Elf())
        file.setExecutable(true, false)
    }
    private fun bundle(): File {
        val root = Files.createTempDirectory("runtime-bundle-test-").toFile()
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=bin/box64\nwine64=wine/bin/wine64\nwineserver=wine/bin/wineserver\nlibrary_dir=lib\n")
        fakeArm64Elf(File(root, "bin/box64"))
        File(root, "wine/bin/wine64").apply { parentFile.mkdirs(); writeBytes(arm64Elf(machine = 62)); setExecutable(true, false) }
        File(root, "wine/bin/wineserver").apply { parentFile.mkdirs(); writeBytes(arm64Elf(machine = 62)); setExecutable(true, false) }
        File(root, "lib/libc.so").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        return root
    }
    private fun arm64Elf(machine: Int = 183): ByteArray {
        // Minimal structurally valid ELF64 ET_DYN with one executable PT_LOAD segment.
        val bytes = ByteArray(120)
        val elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        elf.putShort(16, 3.toShort()) // ET_DYN
        elf.putShort(18, machine.toShort())
        elf.putInt(20, 1)
        elf.putLong(24, 0x400000L)
        elf.putLong(32, 64L)
        elf.putShort(52, 64.toShort())
        elf.putShort(54, 56.toShort())
        elf.putShort(56, 1.toShort())

        elf.putInt(64, 1) // PT_LOAD
        elf.putInt(68, 5) // PF_R | PF_X
        elf.putLong(72, 0L)
        elf.putLong(80, 0x400000L)
        elf.putLong(88, 0x400000L)
        elf.putLong(96, bytes.size.toLong())
        elf.putLong(104, bytes.size.toLong())
        elf.putLong(112, 0x1000L)
        return bytes
    }

    private fun pe32PlusX64(): ByteArray {
        val bytes = ByteArray(368)
        bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x3c, 64)
        bytes[64] = 'P'.code.toByte(); bytes[65] = 'E'.code.toByte()
        b.putShort(68, 0x8664.toShort())
        b.putShort(70, 1.toShort())
        b.putShort(84, 0xF0.toShort())
        b.putShort(88, 0x20b.toShort())
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

    private fun updateJsonComponentHash(root: File, key: String, file: File) {
        val manifestFile = File(root, "runtime-manifest.json")
        val manifest = JSONObject(manifestFile.readText())
        manifest.getJSONObject("components").getJSONObject(key).put("sha256", sha256(file))
        manifestFile.writeText(manifest.toString())
    }

    private fun jsonManifestBundle(): File {
        val root = Files.createTempDirectory("runtime-json-bundle-").toFile()
        val components = JSONObject()

        fun addFile(key: String, relativePath: String, bytes: ByteArray, executable: Boolean = false) {
            val file = File(root, relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            if (executable) file.setExecutable(true, false)
            val abi = when (key) {
                "box64" -> RuntimeBinaryInspector.ELF64_AARCH64
                "wine64", "wineserver" -> RuntimeBinaryInspector.ELF64_X86_64
                "d3d11", "dxgi", "xinput1_3", "xinput1_4", "xinput9_1_0", "vcruntime140", "vcruntime140_1", "msvcp140" -> RuntimeBinaryInspector.PE32_PLUS_X86_64
                else -> "JSON"
            }
            components.put(
                key,
                JSONObject()
                    .put("included", true)
                    .put("path", relativePath)
                    .put("sha256", sha256(file))
                    .put("abi", abi)
            )
        }

        addFile("box64", "box64/bin/box64", arm64Elf(), executable = true)
        addFile("wine64", "wine/bin/wine64", arm64Elf(machine = 62), executable = true)
        addFile("wineserver", "wine/bin/wineserver", arm64Elf(machine = 62), executable = true)
        addFile("d3d11", "dxvk/x64/d3d11.dll", pe32PlusX64())
        addFile("dxgi", "dxvk/x64/dxgi.dll", pe32PlusX64())
        addFile("xinput1_3", "wine/lib/wine/x86_64-windows/xinput1_3.dll", pe32PlusX64())
        addFile("xinput1_4", "wine/lib/wine/x86_64-windows/xinput1_4.dll", pe32PlusX64())
        addFile("xinput9_1_0", "wine/lib/wine/x86_64-windows/xinput9_1_0.dll", pe32PlusX64())
        addFile("vcruntime140", "wine/lib/wine/x86_64-windows/vcruntime140.dll", pe32PlusX64())
        addFile("vcruntime140_1", "wine/lib/wine/x86_64-windows/vcruntime140_1.dll", pe32PlusX64())
        addFile("msvcp140", "wine/lib/wine/x86_64-windows/msvcp140.dll", pe32PlusX64())

        val libraryDirectory = File(root, "wine/lib/wine").apply {
            File(this, "x86_64-unix").mkdirs()
            File(this, "x86_64-windows").mkdirs()
        }
        val guestSharedObject = arm64Elf(machine = 62).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(24, 0L)
        }
        File(libraryDirectory, "module.so").writeBytes(guestSharedObject)
        listOf("ntdll.so", "win32u.so").forEach {
            File(libraryDirectory, "x86_64-unix/$it").writeBytes(guestSharedObject)
        }
        listOf("kernel32.dll", "user32.dll").forEach {
            File(libraryDirectory, "x86_64-windows/$it").writeBytes(pe32PlusX64())
        }
        components.put(
            "wineLib",
            JSONObject()
                .put("included", true)
                .put("path", "wine/lib/wine")
                .put("sha256", directorySha256(libraryDirectory))
                .put("abi", "DIRECTORY_SHA256_X86_64")
        )

        val glibcDirectory = File(root, "wine/lib/x86_64-linux-gnu").apply { mkdirs() }
        listOf("ld-linux-x86-64.so.2", "libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1")
            .forEach { File(glibcDirectory, it).writeBytes(guestSharedObject) }
        components.put(
            "guestGlibc",
            JSONObject()
                .put("included", true)
                .put("path", "wine/lib/x86_64-linux-gnu")
                .put("sha256", directorySha256(glibcDirectory))
                .put("abi", "DIRECTORY_SHA256_X86_64")
        )
        components.put("turnip", JSONObject().put("included", false))

        File(root, "runtime-manifest.json").writeText(
            JSONObject()
                .put("schemaVersion", 2)
                .put("targetAbi", "arm64-v8a")
                .put("provenance", JSONObject().put("packager", "scripts/package_runtime_bundle.sh").put("manifestFormat", "role-validated-abi-v2"))
                .put("components", components)
                .put("defaultLaunchProfile", JSONObject())
                .toString()
        )
        return root
    }

    @Test fun validatesCompleteArm64Bundle() {
        val root = bundle()
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(report.blockers.toString(), RuntimeBundleStatus.READY, report.status)
        assertTrue(report.isReady)
        root.deleteRecursively()
    }
    @Test fun validatesMixedAbiManifestByComponentRole() {
        val root = jsonManifestBundle()
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.READY, report.status)
        assertTrue(report.blockers.toString(), report.isReady)
        root.deleteRecursively()
    }

    @Test fun rejectsMissingWineNtdllModuleEvenWhenDirectoryHashMatches() {
        val root = jsonManifestBundle()
        val ntdll = File(root, "wine/lib/wine/x86_64-unix/ntdll.so")
        ntdll.delete()
        val wineLib = File(root, "wine/lib/wine")
        val manifest = JSONObject(File(root, "runtime-manifest.json").readText())
        manifest.getJSONObject("components").getJSONObject("wineLib")
            .put("sha256", directorySha256(wineLib))
        File(root, "runtime-manifest.json").writeText(manifest.toString())

        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("ntdll.so") })
        root.deleteRecursively()
    }

    @Test fun rejectsMissingGuestDynamicLoaderEvenWhenDirectoryHashMatches() {
        val root = jsonManifestBundle()
        val loader = File(root, "wine/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
        loader.delete()
        val glibc = File(root, "wine/lib/x86_64-linux-gnu")
        val manifest = JSONObject(File(root, "runtime-manifest.json").readText())
        manifest.getJSONObject("components").getJSONObject("guestGlibc")
            .put("sha256", directorySha256(glibc))
        File(root, "runtime-manifest.json").writeText(manifest.toString())

        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("ld-linux-x86-64.so.2") })
        root.deleteRecursively()
    }

    @Test fun rejectsMissingGuestGlibcComponent() {
        val root = jsonManifestBundle()
        val manifest = JSONObject(File(root, "runtime-manifest.json").readText())
        manifest.getJSONObject("components").remove("guestGlibc")
        File(root, "runtime-manifest.json").writeText(manifest.toString())
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("guestGlibc") })
        root.deleteRecursively()
    }

    @Test fun rejectsGuestGlibcDirectoryHashMismatch() {
        val root = jsonManifestBundle()
        File(root, "wine/lib/x86_64-linux-gnu/libc.so.6").appendBytes(byteArrayOf(1))
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("SHA-256 mismatch: guestGlibc") })
        root.deleteRecursively()
    }

    @Test fun rejectsNonElfGuestGlibcWhenDirectoryHashMatches() {
        val root = jsonManifestBundle()
        val libc = File(root, "wine/lib/x86_64-linux-gnu/libc.so.6")
        libc.writeText("not an ELF shared object")
        val manifest = JSONObject(File(root, "runtime-manifest.json").readText())
        manifest.getJSONObject("components").getJSONObject("guestGlibc").put("sha256", directorySha256(libc.parentFile!!))
        File(root, "runtime-manifest.json").writeText(manifest.toString())
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("Guest glibc") && it.contains("ELF64 x86-64") })
        root.deleteRecursively()
    }

    @Test fun validatesAndExposesGuestGlibcDirectory() {
        val root = jsonManifestBundle()
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.READY, report.status)
        assertEquals(File(root, "wine/lib/x86_64-linux-gnu").canonicalFile, report.guestGlibcDirectory?.canonicalFile)
        root.deleteRecursively()
    }

    @Test fun rejectsWrongArchitectureInJsonManifestEvenWhenChecksumMatches() {
        val root = jsonManifestBundle()
        val box64 = File(root, "box64/bin/box64")
        box64.writeBytes(arm64Elf(machine = 0x3e)) // ELF64 little-endian x86_64
        box64.setExecutable(true, false)

        updateJsonComponentHash(root, "box64", box64)

        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(
            "Wrong-architecture Box64 must be blocked even with a matching checksum: ${report.blockers}",
            report.blockers.any { it.contains("box64") && it.contains("ELF64_AARCH64") }
        )
        root.deleteRecursively()
    }

    @Test fun rejectsTruncatedElfHeaderEvenWhenJsonChecksumMatches() {
        val root = jsonManifestBundle()
        val box64 = File(root, "box64/bin/box64")
        box64.writeBytes(arm64Elf().copyOf(20))
        box64.setExecutable(true, false)
        updateJsonComponentHash(root, "box64", box64)

        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("box64") && it.contains("ELF64_AARCH64") })
        root.deleteRecursively()
    }

    @Test fun rejectsOutOfBoundsExecutableLoadSegmentEvenWhenJsonChecksumMatches() {
        val root = jsonManifestBundle()
        val box64 = File(root, "box64/bin/box64")
        val malformed = arm64Elf()
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(96, Long.MAX_VALUE) // PT_LOAD p_filesz exceeds the file.
        box64.writeBytes(malformed)
        box64.setExecutable(true, false)
        updateJsonComponentHash(root, "box64", box64)

        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("box64") && it.contains("ELF64_AARCH64") })
        root.deleteRecursively()
    }

    @Test fun rejectsElfWithoutExecutableLoadSegmentEvenWhenJsonChecksumMatches() {
        val root = jsonManifestBundle()
        val box64 = File(root, "box64/bin/box64")
        val nonExecutable = arm64Elf()
        ByteBuffer.wrap(nonExecutable).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(68, 4) // PT_LOAD is readable but not executable.
        box64.writeBytes(nonExecutable)
        box64.setExecutable(true, false)
        updateJsonComponentHash(root, "box64", box64)

        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.INCOMPLETE, report.status)
        assertTrue(report.blockers.any { it.contains("box64") && it.contains("ELF64_AARCH64") })
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
        assertTrue(report.blockers.any { it.contains("ELF64_AARCH64") })
        root.deleteRecursively()
    }
    @Test fun rejectsManifestPathTraversal() {
        val root = bundle()
        File(root, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=../../escape\nwine64=wine/bin/wine64\nwineserver=wine/bin/wineserver\nlibrary_dir=lib\n")
        assertEquals(RuntimeBundleStatus.INCOMPLETE, RuntimeBundleValidator().validate(root).status)
        root.deleteRecursively()
    }
}
