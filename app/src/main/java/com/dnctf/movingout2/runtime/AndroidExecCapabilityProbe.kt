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
    fun execute(binary: File, arguments: List<String>): ProbeExecution
}

/**
 * Tests actual execve via ProcessBuilder. This is a capability probe, not a generic
 * permission-bit check: Android 10+ SELinux policy can deny execution despite 0755.
 */
class AndroidExecCapabilityProbe(
    private val nativeLibraryDirectory: File,
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
    private val executor: AndroidBinaryExecutor = AndroidBinaryExecutor { binary, args ->
        val before = diagnostics.snapshot().size
        val process = RuntimeProcess(diagnostics).start(binary, args, emptyMap(), binary.parentFile)
        val result = process.waitFor(3000)
        process.stdoutThread.join(500)
        process.stderrThread.join(500)
        val output = diagnostics.snapshot().drop(before)
        ProbeExecution(result.exitCode,
            output.filter { it.component == "stdout" }.joinToString(String(charArrayOf(10))) { it.message },
            output.filter { it.component == "stderr" }.joinToString("\\n") { it.message })
    }
) {
    constructor(
        context: Context,
        diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
        executor: AndroidBinaryExecutor = AndroidBinaryExecutor { binary, args ->
            val before = diagnostics.snapshot().size
            val process = RuntimeProcess(diagnostics).start(binary, args, emptyMap(), binary.parentFile)
            val result = process.waitFor(3000)
            process.stdoutThread.join(500)
            process.stderrThread.join(500)
            val output = diagnostics.snapshot().drop(before)
            ProbeExecution(result.exitCode,
                output.filter { it.component == "stdout" }.joinToString(String(charArrayOf(10))) { it.message },
                output.filter { it.component == "stderr" }.joinToString(String(charArrayOf(10))) { it.message })
        }
    ) : this(File(context.applicationInfo.nativeLibraryDir ?: ""), diagnostics, executor)

    fun probe(appDataExecutable: File): AndroidExecProbeResult {
        val appData = executeCandidate(appDataExecutable, AndroidExecCapability.EXEC_ALLOWED_APP_DATA)
        if (appData.allowed) return appData

        val packaged = File(nativeLibraryDirectory, "libbox64.so")
        val samePayload = packaged.isFile && packaged.canExecute() &&
            RuntimeBinaryInspector.matches(packaged, RuntimeBinaryInspector.ELF64_AARCH64) &&
            sha256(packaged) == sha256(appDataExecutable)
        if (samePayload) {
            val nativeResult = executeCandidate(packaged, AndroidExecCapability.EXEC_ALLOWED_NATIVE_LIB_DIR)
            if (nativeResult.allowed) return nativeResult
            if (nativeResult.capability == AndroidExecCapability.EXEC_BLOCKED_SELINUX_EACCES) return nativeResult
        }
        return appData
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun executeCandidate(file: File, success: AndroidExecCapability): AndroidExecProbeResult {
        if (!file.isFile || !file.canExecute()) {
            return AndroidExecProbeResult(AndroidExecCapability.EXEC_FAILED, file, null,
                "Executable is missing or lacks execute permission: ${file.absolutePath}")
        }
        return try {
            val execution = executor.execute(file, listOf("--version"))
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
