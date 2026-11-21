package com.dnctf.movingout2.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class RuntimeProcessTest {
    private val shell = File("/bin/sh")
    private fun available() = Assume.assumeTrue("POSIX shell required for process integration tests", shell.exists() && shell.canExecute())
    @Test fun pidParserAcceptsOnlyValidPidPatterns() {
        assertEquals(4321L, RuntimeProcess.parsePidSafely("Process[pid=4321, exitValue=0]"))
        assertNull(RuntimeProcess.parsePidSafely("no process pid available"))
        assertNull(RuntimeProcess.parsePidSafely(null))
        assertNull(RuntimeProcess.parsePidSafely("pid=not-a-number"))
    }
    @Test fun capturesStdoutAndStderrInParallelAndEnvironment() {
        available()
        val diagnostics = RuntimeDiagnostics()
        val started = RuntimeProcess(diagnostics).start(shell, listOf("-c", "echo out; echo err >&2; echo ${'$'}TEST_VALUE"), mapOf("TEST_VALUE" to "injected"))
        val result = started.waitFor(3000)
        started.stdoutThread.join(1000); started.stderrThread.join(1000)
        assertEquals(0, result.exitCode)
        val lines = diagnostics.snapshot().map { it.message }
        assertTrue(lines.any { it == "out" })
        assertTrue(lines.any { it == "err" })
        assertTrue(lines.any { it == "injected" })
    }
    @Test fun timeoutTerminatesProcess() {
        available()
        val process = RuntimeProcess(RuntimeDiagnostics()).start(shell, listOf("-c", "sleep 10"))
        val result = process.waitFor(100)
        assertTrue(result.timedOut)
        assertFalse(process.process.isAlive)
        assertNotNull("Terminated process should have an exit code after reaping", result.exitCode)
    }
    @Test fun stopReportsTimeoutWhenProcessCannotBeReaped() {
        val stubborn = object : Process() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = false
            override fun exitValue(): Int = throw IllegalThreadStateException("still running")
            override fun destroy() {}
            override fun destroyForcibly(): Process = this
            override fun isAlive(): Boolean = true
        }
        val result = ProcessResult(stubborn, 123L, Thread {}, Thread {}, System.nanoTime()).stop(10)
        assertTrue(result.terminatedByUser)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
    }

    @Test fun timeoutClosesStreamsAndReaderThreads() {
        available()
        val process = RuntimeProcess(RuntimeDiagnostics()).start(shell, listOf("-c", "sleep 10"))
        val result = process.waitFor(100)
        assertTrue(result.timedOut)
        assertFalse("stdout reader should finish after timeout cleanup", process.stdoutThread.isAlive)
        assertFalse("stderr reader should finish after timeout cleanup", process.stderrThread.isAlive)
    }
    @Test fun stopMarksUserTerminationAndStopsProcess() {
        available()
        val process = RuntimeProcess(RuntimeDiagnostics()).start(shell, listOf("-c", "sleep 10"))
        val result = process.stop(100)
        assertTrue(result.terminatedByUser)
        assertFalse(process.process.isAlive)
    }
}
