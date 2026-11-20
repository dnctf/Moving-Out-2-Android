package com.dnctf.movingout2.runtime

import android.content.Context
import com.dnctf.movingout2.input.RuntimeInputBridge
import java.io.File
import java.io.IOException
import java.util.Properties

sealed class LaunchPreparationResult {
    data class MissingPayload(val blockers: List<String>) : LaunchPreparationResult()
    data class Prepared(val plan: WineLaunchPlan) : LaunchPreparationResult()
}

data class WineLaunchPlan(
    val executable: File,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File,
    val prefix: File,
    val gameExe: File
)

data class WineLaunchOptions(
    val width: Int = 1280,
    val height: Int = 720,
    val fpsCap: Int = 60,
    val useTurnip: Boolean = true,
    val useDxvk: Boolean = true
)

/** Prepares an isolated prefix and builds a Box64 -> Wine launch plan. Presence checks do not prove runtime compatibility. */
class WineRuntimeLauncher(
    private val context: Context,
    private val bundleRoot: File,
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics()
) {
    private val prefix = File(context.filesDir, "runtime/wineprefix")
    private val sharedInput = File(context.filesDir, "xinput_shm.bin")
    private val validator = RuntimeBundleValidator()
    private val inputBridge = RuntimeInputBridge()

    fun prepare(gameExe: File, options: WineLaunchOptions = WineLaunchOptions()): LaunchPreparationResult {
        val report = validator.validate(bundleRoot)
        val blockers = report.blockers.toMutableList()
        if (!report.isReady) return LaunchPreparationResult.MissingPayload(blockers)
        val root = report.root ?: return LaunchPreparationResult.MissingPayload(listOf("Runtime bundle root is unavailable."))
        val properties = Properties()
        runCatching { File(root, "runtime.properties").inputStream().use(properties::load) }
            .onFailure { return LaunchPreparationResult.MissingPayload(listOf("Runtime manifest cannot be read: ${it.message}")) }

        val dxvkDir = resolve(root, properties.getProperty("dxvk_dir"), "DXVK")
        val turnipDir = resolve(root, properties.getProperty("turnip_dir"), "Turnip")
        val box64 = report.box64
        val wine = report.wine64
        val wineserver = report.wineserver
        if (box64 == null || wine == null || wineserver == null) blockers += "Box64/Wine/Wineserver executable paths are missing."
        if (options.useDxvk) {
            if (dxvkDir == null) blockers += "DXVK directory is not configured."
            else for (name in listOf("d3d11.dll", "dxgi.dll")) if (!isValidX64Pe(File(dxvkDir, name))) blockers += "DXVK payload $name is missing or not x64 PE."
        }
        val icd = turnipDir?.walkTopDown()?.maxDepth(8)?.firstOrNull { it.isFile && it.name.endsWith(".json", true) && runCatching { it.readText().contains("library_path") }.getOrDefault(false) }
        if (options.useTurnip && turnipDir == null) blockers += "Turnip directory is not configured."
        if (options.useTurnip && icd == null) blockers += "Turnip ICD JSON with library_path was not found."
        if (!gameExe.isFile || !isValidX64Pe(gameExe)) blockers += "Game executable is missing or not a valid x64 PE: ${gameExe.absolutePath}"
        if (blockers.isNotEmpty()) return LaunchPreparationResult.MissingPayload(blockers.distinct())

        try {
            val system32 = File(prefix, "drive_c/windows/system32")
            if (!system32.mkdirs() && !system32.isDirectory) throw IOException("Unable to create WINEPREFIX system32.")
            val dxvkDlls = if (options.useDxvk) dxvkDir!! else null
            if (dxvkDlls != null) for (dll in listOf("d3d11.dll", "dxgi.dll")) copyFile(File(dxvkDlls, dll), File(system32, dll))
            for (dll in listOf("xinput1_3.dll", "xinput1_4.dll")) {
                val bundleDll = File(root, "xinput/$dll")
                if (bundleDll.isFile) copyFile(bundleDll, File(system32, dll))
                else {
                    val assetDll = File(context.filesDir, "runtime/$dll")
                    if (assetDll.isFile) copyFile(assetDll, File(system32, dll))
                    else context.assets.open("runtime/$dll").use { input -> File(system32, dll).outputStream().use(input::copyTo) }
                }
            }
            sharedInput.parentFile?.mkdirs()
            if (!sharedInput.exists()) sharedInput.writeBytes(ByteArray(128))
            val zPath = toWineZPath(gameExe)
            val args = WineLaunchCommand.arguments(wine.absolutePath, zPath, options.width, options.height)
            val env = WineLaunchCommand.environment(prefix, sharedInput, report.libraryDirectory!!, options.fpsCap, if (options.useTurnip) icd else null, dxvkDlls)
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "launcher", "Prepared Wine prefix and launch plan; execution compatibility remains unverified."))
            return LaunchPreparationResult.Prepared(WineLaunchPlan(box64, args, env, gameExe.parentFile ?: root, prefix, gameExe))
        } catch (e: Exception) {
            return LaunchPreparationResult.MissingPayload(listOf("Wine prefix preparation failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    fun launch(plan: WineLaunchPlan): ProcessResult {
        if (!inputBridge.initialize(sharedInput.absolutePath)) throw IOException("MO2X input bridge initialization failed.")
        return try {
            RuntimeProcess(diagnostics).start(plan.executable, plan.arguments, plan.environment, plan.workingDirectory)
        } catch (error: Exception) {
            neutralizeInputBridge()
            throw error
        }
    }


    fun neutralizeInputBridge() {
        for (player in 0 until 4) runCatching { inputBridge.disconnectPlayer(player) }
    }

    fun stopWineserver(): Boolean {
        val props = Properties()
        return runCatching {
            File(bundleRoot, "runtime.properties").inputStream().use(props::load)
            val rel = props.getProperty("wineserver") ?: return false
            val server = File(bundleRoot, rel).canonicalFile
            if (!server.isFile || !server.canExecute()) return false
            RuntimeProcess(diagnostics).start(server, listOf("-k"), mapOf("WINEPREFIX" to prefix.absolutePath), prefix).waitFor(5000).exitCode == 0
        }.getOrDefault(false)
    }

    fun stop(result: ProcessResult, graceMs: Long = 1200) {
        runCatching { result.stop(graceMs) }
        neutralizeInputBridge()
        stopWineserver()
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "launcher", "Runtime process stopped; wineserver cleanup requested."))
    }

    internal fun toWineZPath(file: File): String {
        val normalized = file.absolutePath.replace('/', '\\')
        return "Z:" + normalized
    }

    private fun resolve(root: File, relative: String?, label: String): File? {
        val value = relative?.trim().orEmpty()
        if (value.isBlank()) return null
        val resolved = runCatching { File(root, value).canonicalFile }.getOrNull() ?: return null
        return if (resolved.toPath().startsWith(root.canonicalFile.toPath()) && resolved.isDirectory && resolved.canRead()) resolved else null
    }

    private fun copyFile(from: File, to: File) {
        if (!from.isFile || !from.canRead()) throw IOException("Required DLL unavailable: ${from.absolutePath}")
        from.inputStream().use { input -> to.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun isValidX64Pe(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 70) return@runCatching false
        val bytes = file.inputStream().use { stream ->\n            val buffer = ByteArray(65536)\n            var offset = 0\n            while (offset < buffer.size) { val read = stream.read(buffer, offset, buffer.size - offset); if (read <= 0) break; offset += read }\n            if (offset == buffer.size) buffer else buffer.copyOf(offset)\n        }
        if (bytes.size < 64 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return@runCatching false
        val pe = (bytes[0x3c].toInt() and 255) or ((bytes[0x3d].toInt() and 255) shl 8) or ((bytes[0x3e].toInt() and 255) shl 16) or ((bytes[0x3f].toInt() and 255) shl 24)
        pe >= 0 && pe <= bytes.size - 6 && bytes[pe] == 'P'.code.toByte() && bytes[pe+1] == 'E'.code.toByte() && bytes[pe+2] == 0.toByte() && bytes[pe+3] == 0.toByte() && bytes[pe+4] == 0x64.toByte() && bytes[pe+5] == 0x86.toByte()
    }.getOrDefault(false)
}
