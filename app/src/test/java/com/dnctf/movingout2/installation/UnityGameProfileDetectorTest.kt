package com.dnctf.movingout2.installation

import org.junit.Assert.*
import org.junit.Test

class UnityGameProfileDetectorTest {
    @Test fun detectsUnityVersionFromBoundedReadableText() {
        val bytes = "prefix\u00002021.3.16f1\u0000suffix".toByteArray(Charsets.US_ASCII)
        assertEquals("2021.3.16f1", UnityGameProfileDetector.detectUnityVersion(bytes))
    }

    @Test fun metadataVersionRequiresMagicAndReadsLittleEndian() {
        val valid = byteArrayOf(0xfa.toByte(), 0xb1.toByte(), 0x1b, 0xaf.toByte(), 0x29, 0, 0, 0)
        assertEquals(41, UnityGameProfileDetector.detectMetadataVersion(valid))
        assertNull(UnityGameProfileDetector.detectMetadataVersion(byteArrayOf(0, 1, 2, 3, 41, 0, 0, 0)))
    }

    @Test fun detectsX64PeArchitecture() {
        val pe = ByteArray(128)
        pe[0] = 'M'.code.toByte(); pe[1] = 'Z'.code.toByte()
        pe[0x3c] = 64
        pe[64] = 'P'.code.toByte(); pe[65] = 'E'.code.toByte()
        pe[68] = 0x64; pe[69] = 0x86.toByte()
        assertEquals("x86_64", UnityGameProfileDetector.detectPeArchitecture(pe))
        assertNull(UnityGameProfileDetector.detectPeArchitecture(byteArrayOf(1, 2, 3)))
    }
}
