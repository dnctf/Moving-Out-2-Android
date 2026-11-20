package com.dnctf.movingout2.runtime

import java.io.File

data class ProcessResult(val process: Process, val stdoutThread: Thread, val stderrThread: Thread)

/** Generic process supervision primitive. RuntimeManager must gate game launch until native runtime is verified. */
class RuntimeProcess(private val diagnostics: RuntimeDiagnostics) {
    fun start(executable: File, args: List<String> = emptyList(), environment: Map<String, String> = emptyMap(), workingDirectory: File? = executable.parentFile): ProcessResult {
        require(executable.exists() && executable.isFile && executable.canExecute()) { "Executable missing or not executable: $executable" }
        val builder = ProcessBuilder(listOf(executable.absolutePath) + args)
        if (workingDirectory != null) builder.directory(workingDirectory)
        builder.environment().putAll(environment)
        val process = builder.start()
        val stdout = Thread({ process.inputStream.bufferedReader().useLines { lines -> lines.forEach { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "stdout", it)) } } }, "runtime-stdout")
        val stderr = Thread({ process.errorStream.bufferedReader().useLines { lines -> lines.forEach { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "stderr", it)) } } }, "runtime-stderr")
        stdout.isDaemon = true; stderr.isDaemon = true
        stdout.start(); stderr.start()
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "process", "Process started: ${executable.name}, pid=${process.pid()}"))
        return ProcessResult(process, stdout, stderr)
    }
}