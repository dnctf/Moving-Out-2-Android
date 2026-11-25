package com.dnctf.movingout2.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary formats are validated by execution role, never by the app's host ABI alone. */
internal object RuntimeBinaryInspector {
    const val ELF64_AARCH64 = "ELF64_AARCH64"
    const val ELF64_X86_64 = "ELF64_X86_64"
    const val PE32_PLUS_X86_64 = "PE32_PLUS_X86_64"

    fun matches(file: File, format: String): Boolean = when (format) {
        ELF64_AARCH64 -> validElf64(file, EM_AARCH64)
        ELF64_X86_64 -> validElf64(file, EM_X86_64)
        PE32_PLUS_X86_64 -> validPe32PlusX64(file)
        else -> false
    }

    fun validElf64(file: File, expectedMachine: Int): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < ELF_HEADER_SIZE) return@use false
            val bytes = ByteArray(ELF_HEADER_SIZE.toInt())
            input.readFully(bytes)
            val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
                bytes[4] != 2.toByte() || bytes[5] != 1.toByte() || bytes[6] != 1.toByte()) return@use false
            val type = h.getShort(16).toInt() and 0xffff
            val machine = h.getShort(18).toInt() and 0xffff
            val version = h.getInt(20)
            val entry = h.getLong(24)
            val phoff = h.getLong(32)
            val ehsize = h.getShort(52).toInt() and 0xffff
            val phentsize = h.getShort(54).toInt() and 0xffff
            val phnum = h.getShort(56).toInt() and 0xffff
            if ((type != ET_EXEC && type != ET_DYN) || machine != expectedMachine || version != 1 ||
                entry <= 0 || ehsize != 64 || phentsize != 56 || phnum !in 1..4096 ||
                phoff < 64 || phoff > length) return@use false
            val tableBytes = phentsize.toLong() * phnum
            if (tableBytes > length - phoff) return@use false
            val ph = ByteArray(56)
            var executableEntry = false
            for (i in 0 until phnum) {
                input.seek(phoff + i.toLong() * phentsize)
                input.readFully(ph)
                val p = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)
                if (p.getInt(0) != PT_LOAD) continue
                val flags = p.getInt(4)
                val offset = p.getLong(8)
                val vaddr = p.getLong(16)
                val filesz = p.getLong(32)
                val memsz = p.getLong(40)
                if (offset < 0 || vaddr < 0 || filesz < 0 || memsz <= 0 || memsz < filesz ||
                    offset > length || filesz > length - offset || vaddr > Long.MAX_VALUE - memsz) return@use false
                if ((flags and PF_X) != 0 && entry >= vaddr && entry < vaddr + memsz) executableEntry = true
            }
            executableEntry
        }
    }.getOrDefault(false)

    fun validPe32PlusX64(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 0x40) return@runCatching false
        RandomAccessFile(file, "r").use { input ->
            val dos = ByteArray(64); input.readFully(dos)
            if (dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return@use false
            val peOffset = ByteBuffer.wrap(dos).order(ByteOrder.LITTLE_ENDIAN).getInt(0x3c)
            if (peOffset < 0 || peOffset.toLong() + 26L > input.length()) return@use false
            input.seek(peOffset.toLong())
            val header = ByteArray(26); input.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            b.get() == 'P'.code.toByte() && b.get() == 'E'.code.toByte() &&
                b.get() == 0.toByte() && b.get() == 0.toByte() &&
                (b.getShort().toInt() and 0xffff) == 0x8664 &&
                (b.getShort(24).toInt() and 0xffff) == 0x20b
        }
    }.getOrDefault(false)

    private const val ELF_HEADER_SIZE = 64L
    private const val ET_EXEC = 2
    private const val ET_DYN = 3
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183
    private const val PT_LOAD = 1
    private const val PF_X = 1
}
