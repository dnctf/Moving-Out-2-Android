package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
    @Test fun validatesCompleteArm64Bundle() {
        val root = bundle()
        val report = RuntimeBundleValidator().validate(root)
        assertEquals(RuntimeBundleStatus.READY, report.status)
        assertTrue(report.isReady)
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
