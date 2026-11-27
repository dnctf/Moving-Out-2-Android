package com.dnctf.movingout2.runtime

import java.io.File
import java.util.concurrent.TimeUnit

data class ExitResult(val exitCode: Int?, val pid: Long?, val timedOut: Boolean, val terminatedByUser: Boolean, val durationMs: Long)

interface ProcessGroupController {
    fun assign(pid: Long, processGroupId: Long): Boolean
    fun signalProcess(pid: Long, signal: Int): Boolean
    fun signalGroup(processGroupId: Long, signal: Int): Boolean
    fun isGroupAlive(processGroupId: Long): Boolean
}

object RuntimeSignals {
    const val SIGKILL = 9
    const val SIGCONT = 18
    const val SIGTERM = 15
}

internal object NativeProcessGroup {
    private val loaded = runCatching { System.loadLibrary("vulkan_probe"); true }.getOrDefault(false)
    private external fun assignNative(pid: Long, processGroupId: Long): Boolean
    private external fun signalProcessNative(pid: Long, signal: Int): Boolean
    private external fun signalGroupNative(processGroupId: Long, signal: Int): Boolean
    private external fun isGroupAliveNative(processGroupId: Long): Boolean
    fun assign(pid: Long, processGroupId: Long): Boolean = loaded && runCatching { assignNative(pid, processGroupId) }.getOrDefault(false)
    fun signalProcess(pid: Long, signal: Int): Boolean = loaded && runCatching { signalProcessNative(pid, signal) }.getOrDefault(false)
    fun signalGroup(processGroupId: Long, signal: Int): Boolean = loaded && runCatching { signalGroupNative(processGroupId, signal) }.getOrDefault(false)
    fun isGroupAlive(processGroupId: Long): Boolean = loaded && runCatching { isGroupAliveNative(processGroupId) }.getOrDefault(false)
}

class AndroidProcessGroupController : ProcessGroupController {
    override fun assign(pid: Long, processGroupId: Long): Boolean = NativeProcessGroup.assign(pid, processGroupId)
    override fun signalProcess(pid: Long, signal: Int): Boolean = NativeProcessGroup.signalProcess(pid, signal)
    override fun signalGroup(processGroupId: Long, signal: Int): Boolean = NativeProcessGroup.signalGroup(processGroupId, signal)
    override fun isGroupAlive(processGroupId: Long): Boolean = NativeProcessGroup.isGroupAlive(processGroupId)
}

data class SpawnedRuntimeProcess(val process: Process, val pid: Long?, val processGroupId: Long?)

object ProcessGroupSpawner {
    private const val STOP_TIMEOUT_MS = 1500L
    private fun shell(): File = listOf(File("/system/bin/sh"), File("/bin/sh"), File("/usr/bin/sh"))
        .firstOrNull { it.isFile && it.canExecute() }
        ?: throw IllegalStateException("No executable shell is available for process-group bootstrap.")

    fun start(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: File?,
        processGroupId: Long?,
        createProcessGroup: Boolean,
        controller: ProcessGroupController
    ): SpawnedRuntimeProcess {
        require(command.isNotEmpty())
        if (!createProcessGroup && processGroupId == null) {
            val process = ProcessBuilder(command).apply {
                if (workingDirectory != null) directory(workingDirectory)
                environment().putAll(environment)
            }.start()
            return SpawnedRuntimeProcess(process, RuntimeProcess.parsePidSafely(process.toString()), null)
        }
        // The child stops before exec so setpgid runs before the exec race window.
        val dollar = 36.toChar()
        val stopScript = "kill -STOP " + dollar + dollar + "; exec \"" + dollar + "@\""
        val shellCommand = listOf(shell().absolutePath, "-c", stopScript, "mo2-process-group") + command
        val child = ProcessBuilder(shellCommand).apply {
            if (workingDirectory != null) directory(workingDirectory)
            environment().putAll(environment)
        }.start()
        val pid = RuntimeProcess.parsePidSafely(child.toString())
            ?: run { child.destroyForcibly(); throw IllegalStateException("Unable to determine process PID for process-group setup.") }
        if (!waitUntilStopped(pid)) {
            child.destroyForcibly()
            throw IllegalStateException("Child process did not enter the process-group bootstrap stop state.")
        }
        val group = processGroupId ?: pid
        if (!controller.assign(pid, group)) {
            controller.signalProcess(pid, RuntimeSignals.SIGKILL)
            child.destroyForcibly()
            throw IllegalStateException("Unable to assign PID $pid to process group $group.")
        }
        if (!controller.signalProcess(pid, RuntimeSignals.SIGCONT)) {
            controller.signalProcess(pid, RuntimeSignals.SIGKILL)
            child.destroyForcibly()
            throw IllegalStateException("Unable to resume process-group child PID $pid.")
        }
        return SpawnedRuntimeProcess(child, pid, group)
    }

    private fun waitUntilStopped(pid: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MS)
        val status = File("/proc/$pid/status")
        while (System.nanoTime() < deadline) {
            val state = runCatching {
                status.useLines { lines ->
                    lines.firstOrNull { it.startsWith("State:") }?.substringAfter("State:")?.trim()?.firstOrNull()
                }
            }.getOrNull()
            if (state == 'T' || state == 't') return true
            if (!status.exists()) return false
            Thread.sleep(5)
        }
        return false
    }
}

data class ProcessResult(
    val process: Process,
    val pid: Long?,
    val stdoutThread: Thread,
    val stderrThread: Thread,
    private val startedNanos: Long,
    val processGroupId: Long? = null,
    private val processGroupController: ProcessGroupController? = null
) {
    fun terminateProcessGroup(gracePeriodMs: Long = 500): Boolean {
        val group = processGroupId ?: return !process.isAlive || stop(gracePeriodMs).let { !process.isAlive }
        val controller = processGroupController ?: return false
        controller.signalGroup(group, RuntimeSignals.SIGTERM)
        val termDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(gracePeriodMs)
        while (System.nanoTime() < termDeadline && controller.isGroupAlive(group)) Thread.sleep(20)
        if (controller.isGroupAlive(group)) {
            controller.signalGroup(group, RuntimeSignals.SIGKILL)
            val killDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FORCE_REAP_TIMEOUT_MS)
            while (System.nanoTime() < killDeadline && controller.isGroupAlive(group)) Thread.sleep(20)
        }
        process.waitFor(FORCE_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        closeStreamsAndJoinReaders()
        return !controller.isGroupAlive(group)
    }

    private val streamCleanupLock = Any()
    private val terminationLock = Any()

    fun waitFor(timeoutMs: Long? = null): ExitResult {
        require(timeoutMs == null || timeoutMs >= 0)
        val timedOut = timeoutMs != null && !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (timedOut) {
            if (processGroupId != null) terminateProcessGroup(250)
            else { terminate(250); closeStreamsAndJoinReaders() }
        } else if (timeoutMs == null) process.waitFor()
        return ExitResult(exitCodeSafely(), pid, timedOut, false, elapsed())
    }

    fun stop(gracePeriodMs: Long = 1000): ExitResult {
        require(gracePeriodMs >= 0)
        val group = processGroupId
        if (group != null && processGroupController != null) {
            val wasAlive = process.isAlive || processGroupController.isGroupAlive(group)
            val stopped = terminateProcessGroup(gracePeriodMs)
            return ExitResult(exitCodeSafely(), pid, !stopped, wasAlive, elapsed())
        }
        val wasAlive = synchronized(terminationLock) {
            val alive = process.isAlive
            if (alive) {
                process.destroy()
                if (!process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(FORCE_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
            alive
        }
        closeStreamsAndJoinReaders()
        return ExitResult(exitCodeSafely(), pid, process.isAlive, wasAlive, elapsed())
    }

    private fun closeStreamsAndJoinReaders() = synchronized(streamCleanupLock) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        listOf(stdoutThread, stderrThread).forEach { reader ->
            if (reader !== Thread.currentThread() && reader.isAlive) {
                reader.interrupt()
                runCatching { reader.join(READER_JOIN_TIMEOUT_MS) }
            }
        }
    }

    private fun terminate(grace: Long): Boolean = synchronized(terminationLock) {
        if (!process.isAlive) return@synchronized true
        process.destroy()
        if (process.waitFor(grace, TimeUnit.MILLISECONDS)) return@synchronized true
        process.destroyForcibly()
        process.waitFor(FORCE_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    private fun exitCodeSafely(): Int? = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
    private fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
    companion object {
        private const val FORCE_REAP_TIMEOUT_MS = 1000L
        private const val READER_JOIN_TIMEOUT_MS = 250L
    }
}

class RuntimeProcess(
    private val diagnostics: RuntimeDiagnostics,
    private val xinputConfig: RuntimeLaunchConfig? = null,
    private val processGroups: ProcessGroupController = AndroidProcessGroupController()
) {
    fun start(
        executable: File,
        args: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        workingDirectory: File? = executable.parentFile,
        processGroupId: Long? = null,
        createProcessGroup: Boolean = false
    ): ProcessResult {
        require(executable.exists() && executable.isFile && executable.canExecute()) { "Executable missing or not executable: $executable" }
        require(workingDirectory == null || (workingDirectory.exists() && workingDirectory.isDirectory)) {
            "Working directory must exist and be a directory: $workingDirectory"
        }
        val effectiveEnvironment = xinputConfig?.environment(environment) ?: environment
        val spawned = ProcessGroupSpawner.start(
            listOf(executable.absolutePath) + args, effectiveEnvironment, workingDirectory,
            processGroupId, createProcessGroup, processGroups
        )
        val started = System.nanoTime()
        val stdout = reader(spawned.process.inputStream, "runtime-stdout", RuntimeDiagnostic.Severity.INFO, "stdout")
        val stderr = reader(spawned.process.errorStream, "runtime-stderr", RuntimeDiagnostic.Severity.ERROR, "stderr")
        stdout.start(); stderr.start()
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "process", "Started ${executable.name}; PID=${spawned.pid ?: "unknown"}; PGID=${spawned.processGroupId ?: "none"}"))
        return ProcessResult(spawned.process, spawned.pid, stdout, stderr, started, spawned.processGroupId, processGroups)
    }

    private fun reader(stream: java.io.InputStream, threadName: String, severity: RuntimeDiagnostic.Severity, component: String) =
        Thread({
            runCatching { stream.bufferedReader().useLines { it.forEach { line -> diagnostics.record(RuntimeDiagnostic(severity, component, line)) } } }
                .onFailure { diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, component, "Reader error: ${it.message}")) }
        }, threadName).apply { isDaemon = true }

    companion object {
        fun parsePidSafely(description: String?): Long? = description?.let {
            Regex("pid=(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
    }
}