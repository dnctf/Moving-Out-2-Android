package com.dnctf.movingout2.compatibility

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounds-checked PE32+ import directory reader. Intended for analysis, not loading binaries. */
object GameBinaryAnalyzer {
    data class Analysis(val machine: Int, val isPe32Plus: Boolean, val imports: Set<String>, val errors: List<String>)

    const val MAX_BINARY_BYTES = 128 * 1024 * 1024
    fun analyze(input: InputStream): Analysis {
        val output = java.io.ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BINARY_BYTES) {
            val remaining = MAX_BINARY_BYTES + 1 - output.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        if (output.size() > MAX_BINARY_BYTES) return Analysis(-1, false, emptySet(), listOf("Binary exceeds 128 MiB analysis limit"))
        return analyze(output.toByteArray())
    }
    fun analyze(bytes: ByteArray): Analysis {
        val imports = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        fun u16(o: Int): Int {
            require(o >= 0 && o.toLong() + 2 <= bytes.size) { "out of bounds u16" }
            return ByteBuffer.wrap(bytes, o, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        }
        fun u32(o: Int): Long {
            require(o >= 0 && o.toLong() + 4 <= bytes.size) { "out of bounds u32" }
            return ByteBuffer.wrap(bytes, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        }
        fun cstr(o: Int): String {
            require(o in bytes.indices) { "invalid string offset" }
            var e = o
            while (e < bytes.size && e - o <= 4096 && bytes[e].toInt() != 0) e++
            require(e < bytes.size && e - o <= 4096) { "unterminated or oversized import name" }
            val name = String(bytes, o, e - o, Charsets.US_ASCII)
            require(name.isNotBlank() && name.all { it.code in 0x20..0x7e }) { "invalid import name" }
            return name
        }
        var machine = -1
        var pe32Plus = false
        if (bytes.size > MAX_BINARY_BYTES) return Analysis(-1, false, emptySet(), listOf("Binary exceeds 128 MiB analysis limit"))
        try {
            require(bytes.size >= 64 && u16(0) == 0x5a4d) { "Invalid DOS MZ" }
            val peOffset = u32(0x3c).toInt()
            require(peOffset >= 0 && peOffset.toLong() + 24 <= bytes.size && u32(peOffset) == 0x00004550L) { "Invalid PE signature" }
            machine = u16(peOffset + 4)
            val sections = u16(peOffset + 6)
            val optionalSize = u16(peOffset + 20)
            val optional = peOffset + 24
            require(optional.toLong() + optionalSize <= bytes.size) { "Truncated optional header" }
            require(optionalSize >= 120) { "Optional header lacks PE32+ import directory fields" }
            require(u16(optional) == 0x20b) { "Not PE32+" }
            pe32Plus = true
            val importRva = u32(optional + 112 + 8).toInt()
            val importSize = u32(optional + 112 + 12).toInt()
            val sectionStart = optional + optionalSize
            require(sectionStart.toLong() + sections.toLong() * 40 <= bytes.size) { "Truncated section table" }
            fun rvaToOffset(rva: Int): Int {
                require(rva >= 0) { "Negative RVA" }
                for (i in 0 until sections) {
                    val s = sectionStart + i * 40
                    val virtualAddress = u32(s + 12).toInt()
                    val virtualSize = u32(s + 8).toInt()
                    val rawSize = u32(s + 16).toInt()
                    val rawPointer = u32(s + 20).toInt()
                    val delta = rva.toLong() - virtualAddress
                    if (delta >= 0 && delta < rawSize && delta < maxOf(virtualSize, rawSize)) {
                        val offset = rawPointer.toLong() + delta
                        require(offset >= 0 && offset < bytes.size) { "RVA raw offset out of bounds" }
                        return offset.toInt()
                    }
                }
                throw IllegalArgumentException("RVA not mapped to raw data: $rva")
            }
            if (importRva != 0 && importSize > 0) {
                require(importSize >= 20) { "Import directory too small" }
                val directoryOffset = rvaToOffset(importRva)
                require(directoryOffset.toLong() + importSize <= bytes.size) { "Import directory exceeds file bounds" }
                val descriptorLimit = minOf(importSize / 20, 4096)
                var terminated = false
                for (i in 0 until descriptorLimit) {
                    val d = directoryOffset.toLong() + i * 20
                    require(d + 20 <= bytes.size && d + 20 <= directoryOffset.toLong() + importSize) { "Import descriptor out of directory bounds" }
                    val descriptor = d.toInt()
                    val original = u32(descriptor)
                    val nameRva = u32(descriptor + 12)
                    val firstThunk = u32(descriptor + 16)
                    if (original == 0L && nameRva == 0L && firstThunk == 0L) { terminated = true; break }
                    require(nameRva != 0L) { "Import descriptor has no name RVA" }
                    imports.add(cstr(rvaToOffset(nameRva.toInt())))
                }
                require(terminated) { "Import descriptor table is not terminated within directory bounds" }
            }
            if (machine != 0x8664) errors.add("Machine is not AMD64 (0x8664)")
        } catch (e: Exception) {
            errors.add(e.message ?: "PE parse failure")
        }
        return Analysis(machine, pe32Plus, imports, errors)
    }
}
