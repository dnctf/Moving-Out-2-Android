package com.dnctf.movingout2.runtime

import java.io.File

/** App-side input IPC path. The Wine-side Z: path is resolved separately by WineLaunchCommand. */
class RuntimeLaunchConfig(private val filesDir: File) {
    val sharedMemoryFile: File
        get() = File(filesDir, "xinput_shm.bin")

    fun prepareSharedMemoryParent(): File {
        val parent = sharedMemoryFile.parentFile ?: filesDir
        require(parent.exists() || parent.mkdirs()) { "Unable to create input bridge directory." }
        return sharedMemoryFile
    }

    fun environment(base: Map<String, String> = emptyMap()): Map<String, String> =
        base + ("MO2_XINPUT_SHM_PATH" to sharedMemoryFile.absolutePath)

    /** App process environment; do not pass this host path directly into Wine without path translation. */
    fun appInputEnvironment(base: Map<String, String> = emptyMap()): Map<String, String> = environment(base)
}
