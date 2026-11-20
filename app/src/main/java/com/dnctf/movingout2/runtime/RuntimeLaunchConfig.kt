package com.dnctf.movingout2.runtime

import java.io.File

/** Supplies a private app-internal MO2X path and launch environment for a future Wine/Box64 process. */
class RuntimeLaunchConfig(private val filesDir: File) {
    val sharedMemoryFile: File
        get() = File(filesDir, "xinput_shm.bin")

    fun environment(base: Map<String, String> = emptyMap()): Map<String, String> =
        base + ("MO2_XINPUT_SHM_PATH" to sharedMemoryFile.absolutePath)
}
