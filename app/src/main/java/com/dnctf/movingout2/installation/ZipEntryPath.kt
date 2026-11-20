package com.dnctf.movingout2.installation

import java.io.IOException

internal object ZipEntryPath {
    fun normalize(raw: String): String {
        val normalized = raw.replace('\\\\', '/').trimEnd('/')
        if (normalized.isBlank() || normalized.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." || it.isBlank() || it == "." }) {
            throw IOException("Unsafe ZIP path: $raw")
        }
        return normalized
    }
}
