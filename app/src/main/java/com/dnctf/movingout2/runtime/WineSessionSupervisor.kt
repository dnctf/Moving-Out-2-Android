package com.dnctf.movingout2.runtime

import android.content.Context
import com.dnctf.movingout2.input.RuntimeInputBridge
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class WineSessionState {
    IDLE,
    VALIDATING_PREFLIGHT,
    BOOTSTRAPPING_PREFIX,
    STARTING_WINESERVER,
    LAUNCHING_TARGET,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}

object WineSessionStateTransitions {
    private val allowed = mapOf(
        WineSessionState.IDLE to setOf(WineSessionState.VALIDATING_PREFLIGHT),
        WineSessionState.VALIDATING_PREFLIGHT to setOf(WineSessionState.BOOTSTRAPPING_PREFIX, WineSessionState.FAILED, WineSessionState.STOPPING),
        WineSessionState.BOOTSTRAPPING_PREFIX to setOf(WineSessionState.STARTING_WINESERVER, WineSessionState.FAILED, WineSessionState.STOPPING),
        WineSessionState.STARTING_WINESERVER to setOf(WineSessionState.LAUNCHING_TARGET, WineSessionState.FAILED, WineSessionState.STOPPING),
        WineSessionState.LAUNCHING_TARGET to setOf(WineSessionState.RUNNING, WineSessionState.FAILED, WineSessionState.STOPPING),
        WineSessionState.RUNNING to setOf(WineSessionState.STOPPING, WineSessionState.FAILED),
        WineSessionState.STOPPING to setOf(WineSessionState.STOPPED, WineSessionState.FAILED),
        WineSessionState.STOPPED to setOf(WineSessionState.IDLE, WineSessionState.VALIDATING_PREFLIGHT),
        WineSessionState.FAILED to setOf(WineSessionState.IDLE, WineSessionState.VALIDATING_PREFLIGHT, WineSessionState.STOPPING, WineSessionState.STOPPED)
    )

    fun canTransition(from: WineSessionState, to: WineSessionState): Boolean =
        to in allowed[from].orEmpty()

    fun transition(from: WineSessionState, to: WineSessionState): WineSessionState {
        require(canTransition(from, to)) { "Invalid wine session transition: $from -> $to" }
        return to
    }
}

interface SessionProcessRunner {
    fun start(
        executable: File,
        args: List<String>,
        environment: Map<String, String>,
        workingDirectory: File?,
        tag: String
    ): ProcessResult

    fun probeWineserverReady(
        box64Executable: File,
        wineserverExecutable: File,
        prefix: File,
        environment: Map<String, String>,
        timeoutMs: Long
    ): Boolean
}

class DefaultSessionProcessRunner(
    private val diagnostics: RuntimeDiagnostics,
    private val logFile: File? = null,
    private val runtimeProcessLogFile: File? = null
) : SessionProcessRunner {
    private val fileLock = Any()

    override fun start(
        executable: File,
        args: List<String>,
        environment: Map<String, String>,
        workingDirectory: File?,
        tag: String
    ): ProcessResult {
        require(executable.exists() && executable.isFile && executable.canExecute()) {
            "Executable missing or not executable: $executable"
        }
        val builder = ProcessBuilder(listOf(executable.absolutePath) + args)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(environment)
        val process = builder.start()
        val pid = RuntimeProcess.parsePidSafely(process.toString())
        val started = System.nanoTime()

        val stdoutThread = pipeReader(process.inputStream, "$tag-stdout", RuntimeDiagnostic.Severity.INFO, tag)
        val stderrThread = pipeReader(process.errorStream, "$tag-stderr", RuntimeDiagnostic.Severity.ERROR, tag)
        stdoutThread.start()
        stderrThread.start()

        recordStructured(RuntimeDiagnostic.Severity.INFO, tag, "Started ${executable.name}; PID=${pid ?: "unknown"}")
        return ProcessResult(process, pid, stdoutThread, stderrThread, started)
    }

    override fun probeWineserverReady(
        box64Executable: File,
        wineserverExecutable: File,
        prefix: File,
        environment: Map<String, String>,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val socketProbeCommand = WineLaunchCommand.wineserverProbeArguments(wineserverExecutable.absolutePath)

        while (System.currentTimeMillis() < deadline) {
            // Check 1: Wineserver socket directory inside prefix
            val wineServerDir = File(prefix, ".wine")
            val socketFound = wineServerDir.listFiles()?.any {
                it.name.startsWith("server-") || it.name.startsWith("wineserver-")
            } == true
            if (socketFound) return true

            // Check 2: Signal 0 probe via wineserver -k0
            try {
                val probeBuilder = ProcessBuilder(listOf(box64Executable.absolutePath) + socketProbeCommand)
                probeBuilder.environment().putAll(environment)
                val probeProc = probeBuilder.start()
                val exited = probeProc.waitFor(200, TimeUnit.MILLISECONDS)
                if (exited && probeProc.exitValue() == 0) {
                    return true
                }
            } catch (_: Exception) {
                // Ignore transient probe errors during startup
            }

            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun pipeReader(stream: java.io.InputStream, threadName: String, severity: RuntimeDiagnostic.Severity, tag: String) =
        Thread({
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        recordStructured(severity, tag, line)
                    }
                }
            }.onFailure {
                recordStructured(RuntimeDiagnostic.Severity.WARNING, tag, "Reader error: ${it.message}")
            }
        }, threadName).apply { isDaemon = true }

    private fun recordStructured(severity: RuntimeDiagnostic.Severity, tag: String, message: String) {
        val formattedMessage = "[$tag] $message"
        diagnostics.record(RuntimeDiagnostic(severity, tag, formattedMessage))
        listOfNotNull(logFile, runtimeProcessLogFile).forEach { file ->
            synchronized(fileLock) {
                runCatching {
                    file.parentFile?.mkdirs()
                    FileWriter(file, true).use { writer ->
                        writer.write("[${System.currentTimeMillis()}] $formattedMessage\n")
                    }
                }
            }
        }
    }
}

class WineSessionSupervisor(
    private val context: Context,
    val bundleRoot: File,
    val gameId: String = "moving-out-2",
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics(),
    private val runner: SessionProcessRunner = DefaultSessionProcessRunner(
        diagnostics,
        File(context.filesDir, "diagnostics/wine_session.log"),
        File(context.filesDir, "diagnostics/runtime-process.log")
    ),
    private val onSessionFinished: (() -> Unit)? = null
) {
    val prefix: File = File(context.filesDir, "wine_prefixes/$gameId")
    private val sharedInput: File = File(context.filesDir, "xinput_shm.bin")
    private val stateRef = AtomicReference(WineSessionState.IDLE)
    private val validator = RuntimeBundleValidator()
    private val inputBridge = RuntimeInputBridge()
    @Volatile private var activeBox64Executable: File? = null
    @Volatile private var activeSessionEnvironment: Map<String, String>? = null
    private val execProbe = AndroidExecCapabilityProbe(context, diagnostics)

    @Volatile var wineserverProcess: ProcessResult? = null
        private set
    @Volatile var targetProcess: ProcessResult? = null
        private set
    @Volatile private var watchdogThread: Thread? = null

    val state: WineSessionState
        get() = stateRef.get()

    @Synchronized
    fun startSession(gameExe: File, options: WineLaunchOptions = WineLaunchOptions()): Boolean {
        val current = stateRef.get()
        if (current != WineSessionState.IDLE && current != WineSessionState.STOPPED && current != WineSessionState.FAILED) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "supervisor", "Cannot start from state $current"))
            return false
        }

        // 1. VALIDATING_PREFLIGHT
        transitionTo(WineSessionState.VALIDATING_PREFLIGHT)
        val report = validator.validate(bundleRoot)
        if (!report.isReady) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Preflight failed: ${report.blockers.joinToString()}"))
            transitionTo(WineSessionState.FAILED)
            return false
        }

        val box64Candidate = report.box64 ?: run {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Box64 executable unavailable"))
            transitionTo(WineSessionState.FAILED)
            return false
        }

        val capability = execProbe.probe(box64Candidate)
        if (!capability.allowed) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Box64 exec probe disallowed: ${capability.detail}"))
            transitionTo(WineSessionState.FAILED)
            return false
        }
        val activeBox64 = capability.executable ?: box64Candidate
        activeBox64Executable = activeBox64

        val wineCandidate = report.wine64 ?: run {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Wine64 binary unavailable"))
            transitionTo(WineSessionState.FAILED)
            return false
        }
        val wineserverCandidate = WineServerResolver.resolve(bundleRoot) ?: run {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "wineserver binary unavailable"))
            transitionTo(WineSessionState.FAILED)
            return false
        }

        if (!gameExe.isFile || !RuntimeBinaryInspector.matches(gameExe, RuntimeBinaryInspector.PE32_PLUS_X86_64)) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Game executable missing or not valid x64 PE: ${gameExe.absolutePath}"))
            transitionTo(WineSessionState.FAILED)
            return false
        }

        // 2. BOOTSTRAPPING_PREFIX
        transitionTo(WineSessionState.BOOTSTRAPPING_PREFIX)
        val bootstrapOk = bootstrapPrefix(activeBox64, wineCandidate, report.libraryDirectory)
        if (!bootstrapOk) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Prefix bootstrap failed"))
            transitionTo(WineSessionState.FAILED)
            return false
        }

        // Setup input IPC & DLLs
        deployXInputDlls()
        configureWinePrefixRegistry()
        sharedInput.parentFile?.mkdirs()
        if (!sharedInput.exists()) sharedInput.writeBytes(ByteArray(128))
        if (!inputBridge.isNativeAvailable() || !inputBridge.initialize(sharedInput.absolutePath)) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor",
                "Native XInput shared-memory bridge is unavailable; refusing to start a session without controller IPC."))
            transitionTo(WineSessionState.FAILED)
            return false
        }

        // Build environment
        val hostLibDir = File(context.applicationInfo.nativeLibraryDir ?: context.applicationInfo.dataDir)
        val env = WineLaunchCommand.environment(
            prefix = prefix,
            sharedInput = sharedInput,
            hostLibraryDirectory = hostLibDir,
            fpsCap = options.fpsCap,
            guestLibraryDirectory = report.libraryDirectory,
            guestBinaryDirectory = wineCandidate.parentFile,
            dxvkDirectory = File(bundleRoot, "dxvk/x64").takeIf { options.useDxvk && it.isDirectory },
            useDxvk = options.useDxvk
        ).toMutableMap()
        env.putAll(options.environmentOverrides)
        activeSessionEnvironment = env.toMap()

        // 3. STARTING_WINESERVER
        transitionTo(WineSessionState.STARTING_WINESERVER)
        val wineserverArgs = WineLaunchCommand.wineserverArguments(wineserverCandidate.absolutePath)
        val serverProc = try {
            runner.start(activeBox64, wineserverArgs, env, prefix, "WINESERVER")
        } catch (e: Exception) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Failed to start wineserver: ${e.message}"))
            transitionTo(WineSessionState.FAILED)
            return false
        }
        wineserverProcess = serverProc

        // Readiness probe
        val isReady = runner.probeWineserverReady(activeBox64, wineserverCandidate, prefix, env, timeoutMs = 8000)
        if (!isReady || !serverProc.process.isAlive) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Wineserver readiness probe timed out or server died"))
            reapWineserver(activeBox64, wineserverCandidate, env)
            transitionTo(WineSessionState.FAILED)
            return false
        }

        // 4. LAUNCHING_TARGET
        transitionTo(WineSessionState.LAUNCHING_TARGET)
        val zPath = WineLaunchCommand.toWineZPath(gameExe.absolutePath)
        val targetArgs = WineLaunchCommand.arguments(wineCandidate.absolutePath, zPath, options.width, options.height)
        val targetProc = try {
            runner.start(activeBox64, targetArgs, env, gameExe.parentFile ?: prefix, "BOX64-WINE")
        } catch (e: Exception) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Failed to launch target game: ${e.message}"))
            reapWineserver(activeBox64, wineserverCandidate, env)
            transitionTo(WineSessionState.FAILED)
            return false
        }
        targetProcess = targetProc

        // 5. RUNNING & Crash Coupling Watchdog
        transitionTo(WineSessionState.RUNNING)
        startCrashCouplingWatchdog(activeBox64, wineserverCandidate, env)
        return true
    }

    private fun bootstrapPrefix(box64: File, wine: File, guestLibraryDirectory: File?): Boolean {
        val systemReg = File(prefix, "system.reg")
        val system32 = File(prefix, "drive_c/windows/system32")
        system32.mkdirs()

        if (systemReg.isFile) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "supervisor", "Existing WINEPREFIX validated at ${prefix.absolutePath}"))
            return true
        }

        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "supervisor", "Initializing new WINEPREFIX at ${prefix.absolutePath}"))
        val bootArgs = WineLaunchCommand.winebootArguments(wine.absolutePath)
        val env = buildMap {
            put("WINEPREFIX", prefix.absolutePath)
            put("WINEDLLOVERRIDES", "xinput1_3,xinput1_4,xinput9_1_0=n,b")
            put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir ?: context.applicationInfo.dataDir)
            put("BOX64_PATH", wine.parentFile?.absolutePath.orEmpty())
            put("BOX64_DYNAREC", "1")
            guestLibraryDirectory?.takeIf { it.isDirectory && it.canRead() }?.let {
                put("BOX64_LD_LIBRARY_PATH", it.absolutePath)
            }
        }

        return try {
            val proc = runner.start(box64, bootArgs, env, prefix, "BOX64-WINE")
            val exit = proc.waitFor(20000)
            if (exit.timedOut || (exit.exitCode != null && exit.exitCode != 0)) {
                diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "wineboot initialization failed: exit=${exit.exitCode}, timedOut=${exit.timedOut}"))
                false
            } else {
                diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "supervisor", "wineboot prefix bootstrap completed successfully"))
                true
            }
        } catch (e: Exception) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "wineboot invocation threw: ${e.message}"))
            false
        }
    }

    private fun configureWinePrefixRegistry() {
        val userReg = File(prefix, "user.reg")
        if (!userReg.isFile) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "supervisor", "Wine user.reg not present after wineboot; registry tuning skipped."))
            return
        }
        val current = runCatching { userReg.readText() }.getOrDefault("")
        val marker = "[Software\\Wine\\Direct3D]"
        if (!current.contains(marker)) {
            userReg.appendText(
                "\n[Software\\Wine\\Direct3D]\n" +
                    "\"csmt\"=\"enabled\"\n" +
                    "\n[Software\\Wine\\WineDbg]\n" +
                    "\"ShowCrashDialog\"=dword:00000000\n"
            )
        }
    }

    private fun deployXInputDlls() {
        val system32 = File(prefix, "drive_c/windows/system32")
        system32.mkdirs()
        for (dll in listOf("xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll", "vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")) {
            val dest = File(system32, dll)
            val bundleDll = when (dll) {
                "xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll" -> File(bundleRoot, "xinput/$dll").takeIf { it.isFile } ?: File(bundleRoot, "wine/lib/wine/x86_64-windows/$dll")
                else -> File(bundleRoot, "wine/lib/wine/x86_64-windows/$dll")
            }
            when {
                bundleDll.isFile -> bundleDll.copyTo(dest, overwrite = true)
                File(context.filesDir, "runtime/$dll").isFile ->
                    File(context.filesDir, "runtime/$dll").copyTo(dest, overwrite = true)
                else -> context.assets.open("runtime/$dll").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (!dest.isFile || dest.length() <= 0L) {
                throw IOException("Required Wine system32 DLL was not installed: $dll")
            }
        }
    }

    private fun startCrashCouplingWatchdog(
        box64: File,
        wineserver: File,
        env: Map<String, String>
    ) {
        watchdogThread = Thread({
            val sProc = wineserverProcess
            val tProc = targetProcess

            while (stateRef.get() == WineSessionState.RUNNING) {
                // If wineserver crashed while running, kill target
                if (sProc != null && !sProc.process.isAlive) {
                    diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "supervisor", "Wineserver process terminated unexpectedly; crash-coupling terminating game target."))
                    tProc?.stop(gracePeriodMs = 200)
                    shutdownAll(box64, wineserver, env, markFailed = true)
                    onSessionFinished?.invoke()
                    break
                }

                // If target exited, reap wineserver
                if (tProc != null && !tProc.process.isAlive) {
                    val exit = runCatching { tProc.process.exitValue() }.getOrNull()
                    val failed = exit != null && exit != 0
                    diagnostics.record(RuntimeDiagnostic(
                        if (failed) RuntimeDiagnostic.Severity.ERROR else RuntimeDiagnostic.Severity.INFO,
                        "supervisor",
                        "Game target exited with code $exit; reaping wineserver."
                    ))
                    shutdownAll(box64, wineserver, env, markFailed = failed)
                    onSessionFinished?.invoke()
                    break
                }

                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "mo2-supervisor-watchdog").apply { isDaemon = true; start() }
    }

    fun onAppBackgrounded() {
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "supervisor", "App backgrounded; initiating idempotent shutdown."))
        shutdownAll()
    }

    @Synchronized
    fun shutdownAll(
        box64Override: File? = null,
        wineserverOverride: File? = null,
        envOverride: Map<String, String>? = null,
        markFailed: Boolean = false
    ): Boolean {
        val curr = stateRef.get()
        if (curr == WineSessionState.STOPPED || curr == WineSessionState.IDLE) return true
        if (curr != WineSessionState.STOPPING) {
            transitionTo(WineSessionState.STOPPING)
        }

        // 1. Neutralize input
        inputBridge.neutralizeAllPlayers()

        // 2. Stop target process
        targetProcess?.let { p ->
            p.stop(gracePeriodMs = 500)
            if (p.process.isAlive) {
                p.process.destroyForcibly()
            }
        }
        targetProcess = null

        // 3. Stop wineserver process via wineserver -k + destroyForcibly fallback
        val b64 = box64Override ?: activeBox64Executable
        val ws = wineserverOverride ?: WineServerResolver.resolve(bundleRoot)
        val env = envOverride ?: activeSessionEnvironment ?: mapOf("WINEPREFIX" to prefix.absolutePath)
        if (b64 != null && ws != null) {
            reapWineserver(b64, ws, env)
        } else {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "supervisor", "Skipping wineserver cleanup: no validated nativeLibraryDir Box64 executable is retained."))
        }
        wineserverProcess?.let { sp ->
            if (sp.process.isAlive) {
                sp.stop(gracePeriodMs = 300)
                if (sp.process.isAlive) sp.process.destroyForcibly()
            }
        }
        wineserverProcess = null

        // 4. Destroy input bridge
        inputBridge.destroy()

        // 5. Final state
        transitionTo(if (markFailed) WineSessionState.FAILED else WineSessionState.STOPPED)
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "supervisor", "Session shutdown completed successfully."))
        return true
    }

    private fun reapWineserver(box64: File, wineserver: File, env: Map<String, String>) {
        runCatching {
            val killArgs = WineLaunchCommand.wineserverKillArguments(wineserver.absolutePath)
            val proc = runner.start(box64, killArgs, env, prefix, "WINESERVER")
            proc.waitFor(2000)
        }
    }

    private fun transitionTo(next: WineSessionState) {
        val prev = stateRef.get()
        if (WineSessionStateTransitions.canTransition(prev, next)) {
            stateRef.set(next)
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "supervisor", "State transitioned: $prev -> $next"))
        } else {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "supervisor", "Ignoring invalid transition: $prev -> $next"))
        }
    }
}
