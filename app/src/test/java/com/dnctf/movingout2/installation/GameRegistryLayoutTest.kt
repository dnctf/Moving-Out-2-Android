package com.dnctf.movingout2.installation

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GameRegistryLayoutTest {
    @Test fun resolvesMo2DataMetadataAndUnityVersionPaths() {
        val root = Files.createTempDirectory("mo2-registry-layout").toFile()
        try {
            File(root, "mo2.exe").writeBytes(byteArrayOf(1))
            val metadata = File(root, "mo2_Data/il2cpp_data/Metadata/global-metadata.dat")
            requireNotNull(metadata.parentFile).mkdirs()
            metadata.writeBytes(byteArrayOf(0xaf.toByte(), 0x1b, 0xb1.toByte(), 0xfa.toByte()))
            val globalManagers = File(root, "mo2_Data/globalgamemanagers")
            requireNotNull(globalManagers.parentFile).mkdirs()
            globalManagers.writeText("Unity 2021.3.16f1")
            assertEquals(File(root, "mo2_Data").canonicalFile, GameRegistryLayout.dataDirectory(root)?.canonicalFile)
            assertEquals(metadata.canonicalFile, GameRegistryLayout.metadataFile(root, "mo2.exe").canonicalFile)
            assertEquals(globalManagers.canonicalFile, GameRegistryLayout.globalManagersFile(root, "mo2.exe").canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun resolvesExecutableAndDataFolderCaseInsensitively() {
        val root = Files.createTempDirectory("mo2-registry-case").toFile()
        try {
            File(root, "MO2.EXE").writeBytes(byteArrayOf(1))
            val metadata = File(root, "MO2_DATA/IL2CPP_DATA/METADATA/GLOBAL-METADATA.DAT")
            requireNotNull(metadata.parentFile).mkdirs()
            metadata.writeBytes(byteArrayOf(1))
            val exe = GameRegistryLayout.executable(root)
            assertEquals("MO2.EXE", exe?.name)
            assertEquals("MO2_DATA", GameRegistryLayout.dataDirectory(root)?.name)
            assertEquals(metadata.canonicalFile, GameRegistryLayout.metadataFile(root, exe!!.name).canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun doesNotTreatLegacyGameFolderAsMo2Data() {
        val root = Files.createTempDirectory("mo2-registry-legacy").toFile()
        try {
            File(root, "mo2.exe").writeBytes(byteArrayOf(1))
            File(root, "MovingOut2_Data").mkdirs()
            assertNull(GameRegistryLayout.dataDirectory(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
