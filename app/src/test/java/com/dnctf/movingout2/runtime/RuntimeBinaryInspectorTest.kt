package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class RuntimeBinaryInspectorTest {
    private fun elf(machine: Int): ByteArray {
        val bytes = ByteArray(120)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0]=0x7f; bytes[1]='E'.code.toByte(); bytes[2]='L'.code.toByte(); bytes[3]='F'.code.toByte()
        bytes[4]=2; bytes[5]=1; bytes[6]=1
        b.putShort(16, 3); b.putShort(18, machine.toShort()); b.putInt(20, 1)
        b.putLong(24, 0x400000); b.putLong(32, 64); b.putShort(52,64); b.putShort(54,56); b.putShort(56,1)
        b.putInt(64,1); b.putInt(68,5); b.putLong(72,0); b.putLong(80,0x400000)
        b.putLong(96,120); b.putLong(104,120)
        return bytes
    }
    @Test fun acceptsSharedObjectsWithZeroEntryPointButStillRequiresExecutableLoadSegment() {
        val root=Files.createTempDirectory("abi-shared").toFile()
        val shared=File(root,"libvulkan_freedreno.so").apply { writeBytes(elf(183)) }
        val sharedBytes = shared.readBytes()
        ByteBuffer.wrap(sharedBytes).order(ByteOrder.LITTLE_ENDIAN).putLong(24, 0L)
        shared.writeBytes(sharedBytes)
        assertTrue(RuntimeBinaryInspector.matches(shared,RuntimeBinaryInspector.ELF64_AARCH64_SHARED))
        assertFalse(RuntimeBinaryInspector.matches(shared,RuntimeBinaryInspector.ELF64_AARCH64))
        root.deleteRecursively()
    }

    @Test fun distinguishesHostArm64AndGuestX8664Elf() {
        val root=Files.createTempDirectory("abi-inspector").toFile()
        val host=File(root,"box64").apply{writeBytes(elf(183))}
        val guest=File(root,"wine64").apply{writeBytes(elf(62))}
        assertTrue(RuntimeBinaryInspector.matches(host,RuntimeBinaryInspector.ELF64_AARCH64))
        assertFalse(RuntimeBinaryInspector.matches(host,RuntimeBinaryInspector.ELF64_X86_64))
        assertTrue(RuntimeBinaryInspector.matches(guest,RuntimeBinaryInspector.ELF64_X86_64))
        assertFalse(RuntimeBinaryInspector.matches(guest,RuntimeBinaryInspector.ELF64_AARCH64))
        root.deleteRecursively()
    }
}
