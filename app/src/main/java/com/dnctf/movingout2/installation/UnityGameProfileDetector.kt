package com.dnctf.movingout2.installation

import java.io.InputStream

data class UnityGameProfile(
    val unityVersion: String?,
    val metadataVersion: Int?,
    val architecture: String?,
    val il2cppDetected: Boolean,
    val confidence: Confidence
) {
    enum class Confidence { CONFIRMED, PARTIAL, UNKNOWN }
}

/** Bounded byte parsers for game profile evidence. Does not load game binaries. */
object UnityGameProfileDetector {
    fun detectUnityVersion(bytes: ByteArray): String? {
        // globalgamemanagers stores a readable Unity version near its header. Scan only
        // printable ASCII, with an explicit bounded input supplied by the caller.
        val text = bytes.take(256 * 1024).map { b ->
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) c.toChar() else ' '
        }.joinToString("")
        return Regex("\\b20\\d{2}\\.\\d+\\.\\d+[abfp]\\d+\\b")
            .find(text)?.value
    }

    fun detectMetadataVersion(header: ByteArray): Int? {
        if (header.size < 8) return null
        val magic = header.take(4).map { it.toInt() and 0xff }
        if (magic != listOf(0xaf, 0x1b, 0xb1, 0xfa)) return null
        return (header[4].toInt() and 0xff) or
            ((header[5].toInt() and 0xff) shl 8) or
            ((header[6].toInt() and 0xff) shl 16) or
            ((header[7].toInt() and 0xff) shl 24)
    }

    fun detectPeArchitecture(header: ByteArray): String? {
        if (header.size < 64 || header[0] != 'M'.code.toByte() || header[1] != 'Z'.code.toByte()) return null
        val offset = (header[0x3c].toInt() and 0xff) or
            ((header[0x3d].toInt() and 0xff) shl 8) or
            ((header[0x3e].toInt() and 0xff) shl 16) or
            ((header[0x3f].toInt() and 0xff) shl 24)
        if (offset < 0 || offset > header.size - 6 || header[offset] != 'P'.code.toByte() ||
            header[offset + 1] != 'E'.code.toByte() || header[offset + 2] != 0.toByte() ||
            header[offset + 3] != 0.toByte()) return null
        val machine = (header[offset + 4].toInt() and 0xff) or ((header[offset + 5].toInt() and 0xff) shl 8)
        return when (machine) { 0x8664 -> "x86_64"; 0x14c -> "x86"; 0xaa64 -> "arm64"; else -> "unknown" }
    }

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
