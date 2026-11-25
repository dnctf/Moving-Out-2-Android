package com.dnctf.movingout2.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AndroidExecCapabilityProbeTest {
    private fun context(nativeDir: File): Context {
        val appInfo = ApplicationInfo().apply { nativeLibraryDir = nativeDir.absolutePath }
        return org.mockito.Mockito.mock(Context::class.java).also {
            org.mockito.Mockito.`when`(it.applicationInfo).thenReturn(appInfo)
        }
    }

    @Test fun reportsAppDataExecutionSuccess() {
        val root = Files.createTempDirectory("exec-probe").toFile()
        val binary = File(root, "box64").apply { writeText("fixture"); setExecutable(true) }
        val probe = AndroidExecCapabilityProbe(context(File(root, "native")), executor =
            AndroidBinaryExecutor { _, _ -> ProbeExecution(0, "box64 version", "") })
        val result = probe.probe(binary)
        assertEquals(AndroidExecCapability.EXEC_ALLOWED_APP_DATA, result.capability)
        assertTrue(result.allowed)
        root.deleteRecursively()
    }

    @Test fun reportsSelinuxEaccesDeterministically() {
        val root = Files.createTempDirectory("exec-probe-eacces").toFile()
        val binary = File(root, "box64").apply { writeText("fixture"); setExecutable(true) }
        val probe = AndroidExecCapabilityProbe(context(File(root, "native")), executor =
            AndroidBinaryExecutor { _, _ -> throw IOException("error=13, Permission denied (EACCES)") })
        val result = probe.probe(binary)
        assertEquals(AndroidExecCapability.EXEC_BLOCKED_SELINUX_EACCES, result.capability)
        assertFalse(result.allowed)
        assertTrue(result.detail.contains("EACCES"))
        root.deleteRecursively()
    }
}
