package com.dnctf.movingout2

import com.dnctf.movingout2.installation.InstallationStatus
import com.dnctf.movingout2.installation.InstallationValidator
import com.dnctf.movingout2.installation.ValidationStage
import com.dnctf.movingout2.installation.InstallationStagePolicy
import com.dnctf.movingout2.installation.GameZipRootLocator
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class InstallationValidatorTest {
    private fun peFixture(machine: Int = 0x8664, optionalMagic: Int = 0x20b): ByteArray {
        val pe = ByteArray(512)
        pe[0] = 'M'.code.toByte(); pe[1] = 'Z'.code.toByte()
        pe[0x3c] = 64.toByte()
        pe[64] = 'P'.code.toByte(); pe[65] = 'E'.code.toByte()
        pe[68] = (machine and 0xff).toByte(); pe[69] = ((machine ushr 8) and 0xff).toByte()
        // COFF SizeOfOptionalHeader and PE32/PE32+ magic
        pe[84] = 224.toByte(); pe[85] = 0.toByte()
        pe[88] = (optionalMagic and 0xff).toByte().toByte(); pe[89] = ((optionalMagic ushr 8) and 0xff).toByte()
        return pe
    }

    private fun fixture(): File {
        val root = Files.createTempDirectory("mo2-fixture").toFile()
        val pe = peFixture()
        listOf("GameAssembly.dll", "UnityPlayer.dll", "baselib.dll").forEach { File(root, it).writeBytes(pe) }
        File(root, "mo2.exe").writeBytes(pe)
        val metadata = File(root, "mo2_Data/il2cpp_data/Metadata/global-metadata.dat")
        metadata.parentFile?.mkdirs()
        metadata.writeBytes(byteArrayOf(0xaf.toByte(), 0x1b.toByte(), 0xb1.toByte(), 0xfa.toByte(), 29.toByte(), 0.toByte(), 0.toByte(), 0.toByte()))
        File(root, "mo2_Data/globalgamemanagers").apply { parentFile.mkdirs(); writeText("Unity 2021.3.16f1 synthetic fixture") }
        return root
    }

    @Test fun sharedCoreStagePolicyRequiresFolderAccessAndCleanRequiredFiles() {
        assertTrue(InstallationStagePolicy.coreFilesPassed(emptyList(), emptyList(), folderAccessPassed = true))
        assertFalse(InstallationStagePolicy.coreFilesPassed(emptyList(), emptyList(), folderAccessPassed = false))
        assertFalse(InstallationStagePolicy.coreFilesPassed(listOf("missing.exe"), emptyList(), folderAccessPassed = true))
        assertFalse(InstallationStagePolicy.coreFilesPassed(emptyList(), listOf("revoked SAF permission"), folderAccessPassed = true))
    }

    @Test fun validX64FilesAreDetectedButRuntimeIsNeverImplied() {
        val root = fixture()
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.VALID, report.status)
        assertEquals("x86_64", report.detectedArchitecture)
        assertEquals(29, report.metadataVersion)
        assertEquals("2021.3.16f1", report.detectedUnityVersion)
        assertEquals("RUNTIME_NOT_INSTALLED", report.runtimeStatus)
        assertFalse(report.isReadyForRuntime)
        assertFalse(report.stages.first { it.stage == ValidationStage.LAUNCH_READINESS }.passed)
        root.deleteRecursively()
    }

    @Test fun missingRequiredFilesAreReported() {
        val root = fixture()
        listOf("GameAssembly.dll", "mo2.exe", "mo2_Data/il2cpp_data/Metadata/global-metadata.dat").forEach { path ->
            File(root, path).delete()
        }
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.INCOMPLETE, report.status)
        assertTrue(report.missingFiles.any { it.contains("GameAssembly.dll") })
        assertTrue(report.missingFiles.any { it.contains("mo2.exe") })
        assertTrue(report.missingFiles.any { it.contains("global-metadata.dat") })
        assertFalse(report.stages.last().passed)
        root.deleteRecursively()
    }

    @Test fun zeroByteRequiredFileIsBlocked() {
        val root = fixture()
        File(root, "baselib.dll").writeBytes(byteArrayOf())
        val report = InstallationValidator().validate(root)
        assertTrue(report.accessErrors.any { it.contains("0 bytes") })
        assertEquals(InstallationStatus.INCOMPLETE, report.status)
        assertFalse(report.stages.last().passed)
        root.deleteRecursively()
    }

    @Test fun missingMetadataStructureIsBlocked() {
        val root = fixture()
        File(root, "mo2_Data").deleteRecursively()
        val report = InstallationValidator().validate(root)
        assertTrue(report.missingFiles.any { it.contains("global-metadata.dat") })
        assertFalse(report.stages.first { it.stage == ValidationStage.STRUCTURE }.passed)
        assertEquals(InstallationStatus.INCOMPLETE, report.status)
        root.deleteRecursively()
    }

    @Test fun revokedSafPermissionBlocksFolderAccessAndReadiness() {
        val root = fixture()
        val report = InstallationValidator().validate(root, persistablePermission = false)
        assertEquals(InstallationStatus.ACCESS_ERROR, report.status)
        assertTrue(report.accessErrors.any { it.contains("SAF") })
        assertFalse(report.stages.first { it.stage == ValidationStage.FOLDER_ACCESS }.passed)
        assertFalse(report.stages.last().passed)
        root.deleteRecursively()
    }

    @Test fun invalidOrTruncatedPeHeaderIsRejectedWithoutCrash() {
        val root = fixture()
        File(root, "mo2.exe").writeBytes(byteArrayOf(0, 1, 2, 3))
        val report = InstallationValidator().validate(root)
        assertNull(report.detectedArchitecture)
        assertEquals(InstallationStatus.INVALID_STRUCTURE, report.status)
        assertFalse(report.stages.first { it.stage == ValidationStage.VERSION_ARCH }.passed
        )
        root.deleteRecursively()
    }

    @Test fun x86Pe32ExecutableIsRejected() {
        val root = fixture()
        File(root, "mo2.exe").writeBytes(peFixture(machine = 0x14c, optionalMagic = 0x10b))
        val report = InstallationValidator().validate(root)
        assertEquals("x86", report.detectedArchitecture)
        assertEquals(InstallationStatus.INVALID_STRUCTURE, report.status)
        assertFalse(report.stages.first { it.stage == ValidationStage.VERSION_ARCH }.passed)
        root.deleteRecursively()
    }

    @Test fun detectsActualMovingOut2LayoutMo2Data() {
        val root = fixture()
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.VALID, report.status)
        assertEquals(29, report.metadataVersion)
        assertTrue(report.missingFiles.isEmpty())
        root.deleteRecursively()
    }

    @Test fun detectsArbitraryUnityExecutableAndMatchingDataDirectory() {
        val root = fixture()
        assertTrue(File(root, "mo2.exe").renameTo(File(root, "MovingOut2-Win64.exe")))
        assertTrue(File(root, "mo2_Data").renameTo(File(root, "MovingOut2-Win64_Data")))
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.VALID, report.status)
        assertEquals("x86_64", report.detectedArchitecture)
        assertEquals(29, report.metadataVersion)
        assertTrue(report.missingFiles.isEmpty())
        root.deleteRecursively()
    }

    @Test fun zipRootLocatorPairsArbitraryUnityExeWithMatchingDataDirectory() {
        val names = listOf(
            "payload/MovingOut2-Win64.exe",
            "payload/GameAssembly.dll",
            "payload/UnityPlayer.dll",
            "payload/baselib.dll",
            "payload/MovingOut2-Win64_Data/il2cpp_data/Metadata/global-metadata.dat"
        )
        assertEquals("payload/", GameZipRootLocator.findRootPrefix(names))
    }

    @Test fun caseInsensitiveLookupSupportsUnityMetadataPath() {
        val root = fixture()
        File(root, "GameAssembly.dll").renameTo(File(root, "gameassembly.DLL"))
        val data = File(root, "mo2_Data")
        val lowerData = File(root, "mo2_data")
        assertTrue(data.renameTo(lowerData))
        val il2cpp = File(lowerData, "il2cpp_data")
        val lowerIl2cpp = File(lowerData, "IL2CPP_DATA")
        assertTrue(il2cpp.renameTo(lowerIl2cpp))
        val metadata = File(lowerIl2cpp, "Metadata")
        val upperMetadata = File(lowerIl2cpp, "METADATA")
        assertTrue(metadata.renameTo(upperMetadata))
        val file = File(upperMetadata, "global-metadata.dat")
        assertTrue(file.renameTo(File(upperMetadata, "GLOBAL-METADATA.DAT")))
        assertEquals(InstallationStatus.VALID, InstallationValidator().validate(root).status)
        root.deleteRecursively()
    }

    @Test fun symlinkEscapingSelectedRootIsBlocked() {
        val root = fixture()
        val outside = Files.createTempFile("outside-game-file", ".dll")
        Files.write(outside, peFixture())
        File(root, "GameAssembly.dll").delete()
        Files.createSymbolicLink(File(root, "GameAssembly.dll").toPath(), outside)
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.INCOMPLETE, report.status)
        assertTrue(report.accessErrors.any { it.contains("escapes selected game root") })
        assertFalse(report.stages.last().passed)
        root.deleteRecursively()
        Files.deleteIfExists(outside)
    }

    @Test fun invalidMetadataMagicBlocksValidation() {
        val root = fixture()
        File(root, "mo2_Data/il2cpp_data/Metadata/global-metadata.dat")
            .writeBytes(byteArrayOf(1, 2, 3, 4, 29, 0, 0, 0))
        val report = InstallationValidator().validate(root)
        assertEquals(InstallationStatus.INVALID_STRUCTURE, report.status)
        assertNull(report.metadataVersion)
        assertTrue("Core file stage must not absorb metadata-header failures", report.stages.first { it.stage == ValidationStage.CORE_FILES }.passed)
        assertFalse(report.stages.first { it.stage == ValidationStage.VERSION_ARCH }.passed
        )
        assertFalse(report.stages.last().passed)
        root.deleteRecursively()
    }

    @Test fun compatibilityStageCannotPassWithoutDeviceAssessment() {
        val root = fixture()
        val report = InstallationValidator().validate(root)
        assertFalse(report.stages.first { it.stage == ValidationStage.COMPATIBILITY }.passed)
        assertTrue(report.stages.first { it.stage == ValidationStage.COMPATIBILITY }.details.any { it.contains("not provided") })
        root.deleteRecursively()
    }
}
