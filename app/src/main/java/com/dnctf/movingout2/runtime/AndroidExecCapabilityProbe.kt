package com.dnctf.movingout2.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class AndroidExecCapability {
    EXEC_ALLOWED_APP_DATA,
    EXEC_ALLOWED_NATIVE_LIB_DIR,
    EXEC_BLOCKED_SELINUX_EACCES,
    EXEC_FAILED
}

data class AndroidExecProbeResult(
    val capability: AndroidExecCapability,
    val executable: File?,
    val exitCode: Int?,
    val detail: String,
    val stdout: String = "",
    val stderr: String = ""
) {
    val allowed: Boolean get() = capability == AndroidExecCapability.EXEC_ALLOWED_APP_DATA ||
        capability == AndroidExecCapability.EXEC_ALLOWED_NATIVE_LIB_DIR
}

data class ProbeExecution(val exitCode: Int?, val stdout: String, val stderr: String)

fun interface AndroidBinaryExecutor {
    @Throws(IOException::class)
    fun execute(binary: File, arguments: List<String>, environment: Map<String, String>): ProbeExecution
}

/**
 * Tests actual execve via ProcessBuilder. This is a capability probe, not a generic
 * permission-bit check: Android 10+ SELinux policy can deny execution despite 0755.
 */
class AndroidExecCapabilityProbe(
    private val nativeLibraryDirectory: File,
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
    private val executor: AndroidBinaryExecutor = AndroidBinaryExecutor { binary, args, environment ->
        val before = diagnostics.snapshot().size
        val process = RuntimeProcess(diagnostics).start(binary, args, environment, binary.parentFile)
        val result = process.waitFor(3000)
        process.stdoutThread.join(500)
        process.stderrThread.join(500)
        val output = diagnostics.snapshot().drop(before)
        ProbeExecution(result.exitCode,
            output.filter { it.component == "stdout" }.joinToString("\n") { it.message },
            output.filter { it.component == "stderr" }.joinToString("\n") { it.message })
    },
    private val allowBundleFallback: Boolean = true
) {
    constructor(
        context: Context,
        diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
        executor: AndroidBinaryExecutor = AndroidBinaryExecutor { binary, args, environment ->
            val before = diagnostics.snapshot().size
            val process = RuntimeProcess(diagnostics).start(binary, args, environment, binary.parentFile)
            val result = process.waitFor(3000)
            process.stdoutThread.join(500)
            process.stderrThread.join(500)
            val output = diagnostics.snapshot().drop(before)
            ProbeExecution(result.exitCode,
                output.filter { it.component == "stdout" }.joinToString("\n") { it.message },
                output.filter { it.component == "stderr" }.joinToString("\n") { it.message })
        }
    ) : this(File(context.applicationInfo.nativeLibraryDir ?: ""), diagnostics, executor, false)

    fun probe(appDataExecutable: File): AndroidExecProbeResult {
        // Android 10+ may deny execve() from filesDir even when POSIX mode is 0755.
        // Prefer the APK-extracted copy from nativeLibraryDir, but only when it is
        // byte-identical to the manifest-validated runtime payload.
        val packaged = File(nativeLibraryDirectory, "libbox64.so")
        if (packaged.exists()) {
            if (!packaged.isFile || !packaged.canExecute() ||
                !RuntimeBinaryInspector.matches(packaged, RuntimeBinaryInspector.ELF64_AARCH64)) {
                return AndroidExecProbeResult(
                    AndroidExecCapability.EXEC_FAILED, packaged, null,
                    "Packaged nativeLibraryDir/libbox64.so is not an executable ARM64 ELF."
                )
            }
            if (!appDataExecutable.isFile || sha256(packaged) != sha256(appDataExecutable)) {
                return AndroidExecProbeResult(
                    AndroidExecCapability.EXEC_FAILED, packaged, null,
                    "Packaged Box64 does not match the hash-validated runtime bundle; refusing mismatched execution."
                )
            }
            return executeCandidate(packaged, AndroidExecCapability.EXEC_ALLOWED_NATIVE_LIB_DIR, appDataExecutable)
        }

        // Only JVM tests may fall back to app-private storage. Android Context instances
        // fail closed: Android 10+ SELinux may deny execve from filesDir regardless of chmod.
        if (!allowBundleFallback) return AndroidExecProbeResult(
            AndroidExecCapability.EXEC_FAILED, appDataExecutable, null,
            "Packaged nativeLibraryDir/libbox64.so is missing; refusing filesDir execution on Android."
        )
        return executeCandidate(appDataExecutable, AndroidExecCapability.EXEC_ALLOWED_APP_DATA, appDataExecutable)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun executeCandidate(file: File, success: AndroidExecCapability, bundleExecutable: File): AndroidExecProbeResult {
        if (!file.isFile || !file.canExecute()) {
            return AndroidExecProbeResult(AndroidExecCapability.EXEC_FAILED, file, null,
                "Executable is missing or lacks execute permission: ${file.absolutePath}")
        }
        return try {
            val bundleRoot = bundleExecutable.parentFile?.parentFile?.parentFile?.canonicalFile
            val environment = buildMap {
                put("LD_LIBRARY_PATH", nativeLibraryDirectory.absolutePath)
                if (bundleRoot != null) {
                    put("BOX64_PATH", File(bundleRoot, "wine/bin").absolutePath)
                    put("BOX64_LD_LIBRARY_PATH", File(bundleRoot, "wine/lib/wine").absolutePath)
                }
            }
            val execution = executor.execute(file, listOf("--version"), environment)
            if (execution.exitCode == 0) {
                val result = AndroidExecProbeResult(success, file, 0,
                    "Binary execution succeeded from ${file.parentFile?.absolutePath}.",
                    execution.stdout, execution.stderr)
                diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "exec-probe",
                    "${result.capability}: ${result.detail}"))
                result
            } else {
                AndroidExecProbeResult(AndroidExecCapability.EXEC_FAILED, file, execution.exitCode,
                    "Probe exited with code ${execution.exitCode}. stderr=${execution.stderr}",
                    execution.stdout, execution.stderr)
            }
        } catch (error: IOException) {
            val message = error.message.orEmpty()
            val blocked = message.contains("EACCES", true) || message.contains("permission denied", true) ||
                message.contains("Operation not permitted", true)
            val capability = if (blocked) AndroidExecCapability.EXEC_BLOCKED_SELINUX_EACCES else AndroidExecCapability.EXEC_FAILED
            val detail = "${if (blocked) "EXEC_BLOCKED_SELINUX_EACCES" else "EXEC_FAILED"}: ${message.ifBlank { error.javaClass.simpleName }}"
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "exec-probe", detail))
            AndroidExecProbeResult(capability, file, null, detail, stderr = message)
        } catch (error: Exception) {
            val detail = "Probe failed: ${error.message ?: error.javaClass.simpleName}"
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "exec-probe", detail))
            AndroidExecProbeResult(AndroidExecCapability.EXEC_FAILED, file, null, detail)
        }
    }
}
