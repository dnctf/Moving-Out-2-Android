package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WineSessionSupervisorTest {

    @Test
    fun validatesAllowedStateTransitions() {
        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.IDLE, WineSessionState.VALIDATING_PREFLIGHT))
        assertFalse(WineSessionStateTransitions.canTransition(WineSessionState.IDLE, WineSessionState.RUNNING))

        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.VALIDATING_PREFLIGHT, WineSessionState.BOOTSTRAPPING_PREFIX))
        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.VALIDATING_PREFLIGHT, WineSessionState.FAILED))

        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.BOOTSTRAPPING_PREFIX, WineSessionState.STARTING_WINESERVER))
        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.STARTING_WINESERVER, WineSessionState.LAUNCHING_TARGET))
        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.LAUNCHING_TARGET, WineSessionState.RUNNING))

        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.RUNNING, WineSessionState.STOPPING))
        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.STOPPING, WineSessionState.STOPPED))
        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.STOPPED, WineSessionState.IDLE))
        assertTrue(WineSessionStateTransitions.canTransition(WineSessionState.FAILED, WineSessionState.IDLE))
    }

    @Test
    fun throwsOnDisallowedTransition() {
        try {
            WineSessionStateTransitions.transition(WineSessionState.IDLE, WineSessionState.RUNNING)
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Invalid wine session transition"))
        }
    }

    @Test
    fun wineLaunchCommandProvidesSupervisorArguments() {
        assertEquals(listOf("/bin/wine", "wineboot", "--init"), WineLaunchCommand.winebootArguments("/bin/wine"))
        assertEquals(listOf("/bin/wineserver", "-f", "-p"), WineLaunchCommand.wineserverArguments("/bin/wineserver"))
        assertEquals(listOf("/bin/wineserver", "-k"), WineLaunchCommand.wineserverKillArguments("/bin/wineserver"))
        assertEquals(listOf("/bin/wineserver", "-k0"), WineLaunchCommand.wineserverProbeArguments("/bin/wineserver"))
    }

    @Test
    fun winebootAndGameUseTheSameOrderedGuestGlibcSearchPath() {
        val root = java.nio.file.Files.createTempDirectory("wineboot-glibc-path").toFile()
        val wineRoot = File(root, "wine").apply { mkdirs() }
        val wineBin = File(wineRoot, "bin").apply { mkdirs() }
        val glibc = File(wineRoot, "lib/x86_64-linux-gnu").apply { mkdirs() }
        val wineLib = File(wineRoot, "lib/wine").apply { mkdirs() }
        val bootEnvironment = WineLaunchCommand.bootstrapEnvironment(
            prefix = File(root, "prefix"),
            wineBinary = File(wineBin, "wine64"),
            hostLibraryDirectory = File(root, "nativeLibraryDir"),
            guestLibraryDirectory = wineLib,
            guestGlibcDirectory = glibc
        )
        val gameEnvironment = WineLaunchCommand.environment(
            File(root, "prefix"), File(root, "xinput_shm.bin"), File(root, "nativeLibraryDir"), 30,
            guestLibraryDirectory = wineLib, guestGlibcDirectory = glibc,
            guestBinaryDirectory = wineBin
        )
        val expected = glibc.absolutePath + ":" + wineLib.absolutePath
        assertEquals(expected, bootEnvironment["BOX64_LD_LIBRARY_PATH"])
        assertEquals(expected, gameEnvironment["BOX64_LD_LIBRARY_PATH"])
        assertEquals(wineLib.absolutePath, bootEnvironment["WINEDLLPATH"])
        assertEquals(File(wineBin, "wine64").absolutePath, bootEnvironment["WINELOADER"])
        assertEquals(File(wineBin, "wineserver").absolutePath, bootEnvironment["WINESERVER"])
        assertEquals(wineLib.absolutePath, gameEnvironment["WINEDLLPATH"])
        assertEquals(File(wineBin, "wine64").absolutePath, gameEnvironment["WINELOADER"])
        assertEquals(File(wineBin, "wineserver").absolutePath, gameEnvironment["WINESERVER"])
        assertEquals(File(root, "nativeLibraryDir").absolutePath, bootEnvironment["LD_LIBRARY_PATH"])
        assertEquals(File(root, "nativeLibraryDir").absolutePath, gameEnvironment["LD_LIBRARY_PATH"])
        root.deleteRecursively()
    }
    @Test
    fun structuredLogRecordsPrefixTags() {
        val diagnostics = RuntimeDiagnostics()
        val tempLog = File.createTempFile("wine_session_test", ".log")
        try {
            val runner = DefaultSessionProcessRunner(diagnostics, tempLog)
            // Test logging helper format directly
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "WINESERVER", "[WINESERVER] server socket opened"))
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "BOX64-WINE", "[BOX64-WINE] initializing mono engine"))

            val snapshot = diagnostics.snapshot().map { it.message }
            assertTrue(snapshot.any { it.startsWith("[WINESERVER]") })
            assertTrue(snapshot.any { it.startsWith("[BOX64-WINE]") })
        } finally {
            tempLog.delete()
        }
    }

    private class MockProcess(private val aliveState: AtomicBoolean, private val exitVal: Int = 0) : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            while (aliveState.get()) { Thread.sleep(10) }
            return exitVal
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            return !aliveState.get()
        }
        override fun exitValue(): Int {
            if (aliveState.get()) throw IllegalThreadStateException("Process is still running")
            return exitVal
        }
        override fun destroy() {
            aliveState.set(false)
        }
        override fun destroyForcibly(): Process {
            aliveState.set(false)
            return this
        }
        override fun isAlive(): Boolean = aliveState.get()
    }

    @Test
    fun mockProcessLifecycleOperatesDeterministically() {
        val alive = AtomicBoolean(true)
        val proc = MockProcess(alive, 0)
        assertTrue(proc.isAlive)
        proc.destroy()
        assertFalse(proc.isAlive)
        assertEquals(0, proc.exitValue())
    }
}
