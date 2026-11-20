package com.dnctf.movingout2.runtime

import java.io.File
import java.util.Properties

/** Resolves wineserver safely from either supported bundle format. */
object WineServerResolver {
    fun resolve(bundleRoot: File): File? {
        val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
        val manifest = File(root, "runtime-manifest.json")
        val candidate = if (manifest.isFile) {
            val validation = RuntimeManifestParser().validate(root)
            if (!validation.valid) return null
            validation.componentFiles["wineserver"]
        } else {
            val propsFile = File(root, "runtime.properties")
            if (!propsFile.isFile || !propsFile.canRead()) return null
            val props = runCatching { Properties().apply { propsFile.inputStream().use(::load) } }.getOrNull() ?: return null
            val relative = props.getProperty("wineserver")?.trim().orEmpty()
            if (relative.isBlank()) return null
            runCatching { File(root, relative).canonicalFile }.getOrNull()
        } ?: return null
        val safe = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return safe.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile && it.canRead() && it.canExecute() && it.length() > 0 }
    }
}
