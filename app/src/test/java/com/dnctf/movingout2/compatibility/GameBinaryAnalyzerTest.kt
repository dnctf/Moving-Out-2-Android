package com.dnctf.movingout2.compatibility

import org.junit.Assert.*
import org.junit.Test

class GameBinaryAnalyzerTest {
    private fun peWithImport(dll: String = "xinput1_3.dll", machine: Int = 0x8664): ByteArray {
        val bytes = ByteArray(1536)
        fun put16(offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset+1] = (value ushr 8).toByte() }
        fun put32(offset: Int, value: Int) { for (i in 0..3) bytes[offset+i] = (value ushr (i*8)).toByte() }
        bytes[0]='M'.code.toByte(); bytes[1]='Z'.code.toByte()
        put32(0x3c, 64); bytes[64]='P'.code.toByte(); bytes[65]='E'.code.toByte()
        put16(68, machine); put16(70, 1); put16(84, 240); put16(88, 0x20b)
        put32(208, 0x1010); put32(212, 40)
        val section = 328
        put32(section+8, 0x400); put32(section+12, 0x1000); put32(section+16, 0x400); put32(section+20, 0x200)
        val descriptor = 0x210
        put32(descriptor, 0x1040); put32(descriptor+12, 0x1050); put32(descriptor+16, 0x1040)
        val name = dll.toByteArray(Charsets.US_ASCII)
        name.copyInto(bytes, 0x250); bytes[0x250+name.size] = 0
        return bytes
    }
    @Test fun parsesImportDirectoryAndDllNames() {
        val result = GameBinaryAnalyzer.analyze(peWithImport())
        assertTrue(result.isPe32Plus)
        assertEquals(0x8664, result.machine)
        assertEquals(setOf("xinput1_3.dll"), result.imports)
        assertTrue(result.errors.isEmpty())
    }
    @Test fun nonX64MachineIsReported() {
        val result = GameBinaryAnalyzer.analyze(peWithImport(machine=0x14c))
        assertEquals(0x14c, result.machine)
        assertTrue(result.errors.any { it.contains("AMD64") })
    }
    @Test fun malformedOrTruncatedPeIsReportedWithoutCrash() {
        assertFalse(GameBinaryAnalyzer.analyze(byteArrayOf(1,2,3)).isPe32Plus)
        val truncated = peWithImport().copyOf(300)
        val result = GameBinaryAnalyzer.analyze(truncated)
        assertFalse(result.isPe32Plus || result.imports.isNotEmpty())
        assertTrue(result.errors.isNotEmpty())
    }
    @Test fun unterminatedImportNameIsRejected() {
        val bytes = peWithImport(dll="xinput.dll")
        bytes.fill(65.toByte(), 0x250, 0x250+4097.coerceAtMost(bytes.size-0x250))
        val result = GameBinaryAnalyzer.analyze(bytes)
        assertTrue(result.errors.isNotEmpty())
    }
}
