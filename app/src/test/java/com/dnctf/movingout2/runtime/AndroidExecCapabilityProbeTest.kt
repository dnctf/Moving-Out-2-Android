package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidExecCapabilityProbeTest {

    private fun arm64Elf(): ByteArray {
        val bytes = ByteArray(120)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0]=0x7f; bytes[1]='E'.code.toByte(); bytes[2]='L'.code.toByte(); bytes[3]='F'.code.toByte()
        bytes[4]=2; bytes[5]=1; bytes[6]=1
        b.putShort(16, 3.toShort()); b.putShort(18, 183.toShort()); b.putInt(20, 1)
        b.putLong(24, 0x400000); b.putLong(32, 64); b.putShort(52,64.toShort()); b.putShort(54,56.toShort()); b.putShort(56,1.toShort())
        b.putInt(64,1); b.putInt(68,5); b.putLong(72,0); b.putLong(80,0x400000)
        b.putLong(96,120); b.putLong(104,120)
        return bytes
    }


    @Test fun reportsAppDataExecutionSuccess() {
        val root = Files.createTempDirectory("exec-probe").toFile()
        val binary = File(root, "box64").apply { writeText("fixture"); setExecutable(true) }
        val probe = AndroidExecCapabilityProbe(File(root, "native"), executor =
            AndroidBinaryExecutor { _, _ -> ProbeExecution(0, "box64 version", "") })
        val result = probe.probe(binary)
        assertEquals(AndroidExecCapability.EXEC_ALLOWED_APP_DATA, result.capability)
        assertTrue(result.allowed)
        root.deleteRecursively()
    }

    @Test fun reportsSelinuxEaccesDeterministically() {
        val root = Files.createTempDirectory("exec-probe-eacces").toFile()
        val binary = File(root, "box64").apply { writeText("fixture"); setExecutable(true) }
        val probe = AndroidExecCapabilityProbe(File(root, "native"), executor =
            AndroidBinaryExecutor { _, _ -> throw IOException("error=13, Permission denied (EACCES)") })
        val result = probe.probe(binary)
        assertEquals(AndroidExecCapability.EXEC_BLOCKED_SELINUX_EACCES, result.capability)
        assertFalse(result.allowed)
        assertTrue(result.detail.contains("EACCES"))
        root.deleteRecursively()
    }
}
