package com.dnctf.movingout2.runtime

import java.io.File
import java.util.concurrent.TimeUnit

data class ExitResult(val exitCode: Int?, val pid: Long?, val timedOut: Boolean, val terminatedByUser: Boolean, val durationMs: Long)
data class ProcessResult(val process: Process, val pid: Long?, val stdoutThread: Thread, val stderrThread: Thread, private val startedNanos: Long) {
    fun waitFor(timeoutMs: Long? = null): ExitResult {
        require(timeoutMs == null || timeoutMs >= 0)
        val timedOut = timeoutMs != null && !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (timedOut) terminate(250)
        else if (timeoutMs == null) process.waitFor()
        return ExitResult(exitCodeSafely(), pid, timedOut, false, elapsed())
    }
    fun stop(gracePeriodMs: Long = 1000): ExitResult {
        require(gracePeriodMs >= 0)
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                // Forced termination is also bounded; retain a nullable exit code if
                // the OS/process implementation has not reaped it within this window.
                process.waitFor(FORCE_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
        return ExitResult(exitCodeSafely(), pid, false, true, elapsed())
    }
    private fun terminate(grace: Long): Boolean {
        if (!process.isAlive) return true
        process.destroy()
        if (process.waitFor(grace, TimeUnit.MILLISECONDS)) return true
        process.destroyForcibly()
        return process.waitFor(FORCE_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    private fun exitCodeSafely(): Int? = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
    private fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
    companion object {
        private const val FORCE_REAP_TIMEOUT_MS = 1000L
    }
}

class RuntimeProcess(private val diagnostics: RuntimeDiagnostics, private val xinputConfig: RuntimeLaunchConfig? = null) {
    fun start(executable: File, args: List<String> = emptyList(), environment: Map<String, String> = emptyMap(), workingDirectory: File? = executable.parentFile): ProcessResult {
        require(executable.exists() && executable.isFile && executable.canExecute()) { "Executable missing or not executable: $executable" }
        val builder = ProcessBuilder(listOf(executable.absolutePath) + args)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(xinputConfig?.environment(environment) ?: environment)
        val process = builder.start()
        val pid = parsePidSafely(process.toString())
        val started = System.nanoTime()
        val stdout = reader(process.inputStream, "runtime-stdout", RuntimeDiagnostic.Severity.INFO, "stdout")
        val stderr = reader(process.errorStream, "runtime-stderr", RuntimeDiagnostic.Severity.ERROR, "stderr")
        stdout.start(); stderr.start()
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "process", "Started ${executable.name}; PID=${pid ?: "unknown"}"))
        return ProcessResult(process, pid, stdout, stderr, started)
    }
    private fun reader(stream: java.io.InputStream, threadName: String, severity: RuntimeDiagnostic.Severity, component: String) =
        Thread({
            runCatching { stream.bufferedReader().useLines { it.forEach { line -> diagnostics.record(RuntimeDiagnostic(severity, component, line)) } } }
                .onFailure { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, component, "Reader error: ${it.message}")) }
        }, threadName).apply { isDaemon = true }
    companion object {
        fun parsePidSafely(description: String?): Long? = description?.let { Regex("pid=(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
    }
}
