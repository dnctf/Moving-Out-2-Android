package com.dnctf.movingout2.installation

import java.io.IOException

internal class ZipEntryPathRegistry {
    private val seen = HashSet<String>()
    fun register(raw: String): String {
        val normalized = ZipEntryPath.normalize(raw)
        if (!seen.add(normalized.lowercase(java.util.Locale.ROOT))) throw IOException("Duplicate ZIP entry: $normalized")
        return normalized
    }
}

internal object ZipEntryPath {
    fun normalize(raw: String): String {
        val normalized = raw.replace('\\', '/').trimEnd('/')
        if (normalized.isBlank() || normalized.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." || it.isBlank() || it == "." }) {
            throw IOException("Unsafe ZIP path: $raw")
        }
        return normalized
    }
}
