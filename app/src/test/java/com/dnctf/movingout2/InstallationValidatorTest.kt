package com.dnctf.movingout2

import com.dnctf.movingout2.installation.InstallationStatus
import com.dnctf.movingout2.installation.InstallationValidator
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InstallationValidatorTest {
    private fun fixture(): File {
        val root = createTempDir(prefix = "mo2-fixture")
        val pe = ByteArray(512)
        pe[0] = 0x4d; pe[1] = 0x5a
        pe[0x3c] = 64
        pe[64] = 0x50; pe[65] = 0x45
        pe[68] = 0x64.toByte(); pe[69] = 0x86.toByte()
        listOf("GameAssembly.dll", "UnityPlayer.dll", "baselib.dll").forEach { File(root, it).writeBytes(pe) }
        val exe = File(root, "mo2.exe")
        exe.writeBytes(pe)
        val metadata = File(root, "MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat")
        metadata.parentFile.mkdirs()
        metadata.writeBytes(byteArrayOf(0xFA.toByte(), 0xB1.toByte(), 0x1B, 0xAF.toByte(), 0x29, 0, 0, 0))
        return root
    }

    @Test fun validX64InstallationIsDetected() {
        val root = fixture()
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.VALID, report.status)
        assertEquals("x86_64", report.detectedArchitecture)
        assertEquals("RUNTIME_NOT_INSTALLED", report.runtimeStatus)
        root.deleteRecursively()
    }

    @Test fun missingCoreFilesAreReported() {
        val root = fixture()
        File(root, "GameAssembly.dll").delete()
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.INCOMPLETE, report.status)
        assertTrue(report.missingFiles.any { it.contains("GameAssembly.dll") })
        root.deleteRecursively()
    }

    @Test fun zeroByteCoreFileIsReported() {
        val root = fixture()
        File(root, "baselib.dll").writeBytes(byteArrayOf())
        val report = InstallationValidator().validate(root)
        assertTrue(report.accessErrors.any { it.contains("0 bytes") })
        assertEquals(InstallationStatus.INCOMPLETE, report.status)
        root.deleteRecursively()
    }

    @Test fun missingMetadataDirectoryIsReported() {
        val root = fixture()
        File(root, "MovingOut2_Data").deleteRecursively()
        val report = InstallationValidator().validate(root)
        assertTrue(report.missingFiles.any { it.contains("global-metadata.dat") })
        assertFalse(report.stages.first { it.stage.name == "STRUCTURE" }.passed)
        root.deleteRecursively()
    }

    @Test fun revokedSafPermissionIsReported() {
        val root = fixture()
        val report = InstallationValidator().validate(root, persistablePermission = false)
        assertEquals(InstallationStatus.ACCESS_ERROR, report.status)
        assertTrue(report.accessErrors.any { it.contains("SAF") })
        root.deleteRecursively()
    }

    @Test fun invalidPeHeaderIsRejectedAsUnknownArchitecture() {
        val root = fixture()
        File(root, "mo2.exe").writeBytes(byteArrayOf(0, 1, 2, 3))
        val report = InstallationValidator().validate(root)
        assertEquals(null, report.detectedArchitecture)
        assertEquals(InstallationStatus.INVALID_STRUCTURE, report.status)
        root.deleteRecursively()
    }

    @Test fun pathLookupIsCaseInsensitive() {
        val root = fixture()
        File(root, "GameAssembly.dll").renameTo(File(root, "gameassembly.DLL"))
        assertEquals(InstallationStatus.VALID, InstallationValidator().validate(root).status)
        root.deleteRecursively()
    }
}
    @Test fun corruptPeDllIsRejected() {
        val root = fixture()
        File(root, "baselib.dll").writeBytes(byteArrayOf(1, 2, 3, 4))
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.INVALID_STRUCTURE, report.status)
        assertFalse(report.stages.first { it.stage.name == "VERSION_ARCH" }.passed)
        root.deleteRecursively()
    }
