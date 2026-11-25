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
    val useDxvk: Boolean = true,
    val environmentOverrides: Map<String, String> = emptyMap()
)

/** Prepares an isolated prefix and builds a Box64 -> Wine launch plan. Presence checks do not prove runtime compatibility. */
class WineRuntimeLauncher(
    private val context: Context,
    val bundleRoot: File,
    private val diagnostics: RuntimeDiagnostics = RuntimeDiagnostics()
) {
    private val prefix = File(context.filesDir, "runtime/wineprefix")
    private val sharedInput = File(context.filesDir, "xinput_shm.bin")
    private val validator = RuntimeBundleValidator()
    private val inputBridge = RuntimeInputBridge()
    private val execCapabilityProbe = AndroidExecCapabilityProbe(context, diagnostics)
    @Volatile private var activeBox64Executable: File? = null

    fun prepare(gameExe: File, options: WineLaunchOptions = WineLaunchOptions()): LaunchPreparationResult {
        activeBox64Executable = null
        val report = validator.validate(bundleRoot)
        val blockers = report.blockers.toMutableList()
        if (!report.isReady) return LaunchPreparationResult.MissingPayload(blockers)
        val root = report.root ?: return LaunchPreparationResult.MissingPayload(listOf("Runtime bundle root is unavailable."))
        val box64Candidate = report.box64 ?: return LaunchPreparationResult.MissingPayload(listOf("Box64 host executable is unavailable."))
        val execCapability = execCapabilityProbe.probe(box64Candidate)
        if (!execCapability.allowed) {
            val detail = when (execCapability.capability) {
                AndroidExecCapability.EXEC_BLOCKED_SELINUX_EACCES ->
                    "Android denied executable launch (EACCES/SELinux execute_no_trans). Runtime launch is blocked before wineserver startup. ${execCapability.detail}"
                else -> "Android executable capability probe failed; runtime launch is blocked before wineserver startup. ${execCapability.detail}"
            }
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "exec-probe", detail))
            return LaunchPreparationResult.MissingPayload(listOf(detail))
        }
        activeBox64Executable = execCapability.executable ?: box64Candidate
        val properties = Properties()
        val manifestFile = File(root, "runtime-manifest.json")
        if (manifestFile.isFile) {
            val parsed = RuntimeManifestParser().validate(root)
            if (!parsed.valid) return LaunchPreparationResult.MissingPayload(parsed.errors)
            val manifest = parsed.manifest ?: return LaunchPreparationResult.MissingPayload(listOf("Runtime manifest JSON unavailable."))
            properties.setProperty("box64", parsed.componentFiles["box64"]?.relativeTo(root)?.path ?: "")
            properties.setProperty("wine64", parsed.componentFiles["wine64"]?.relativeTo(root)?.path ?: "")
            properties.setProperty("wineserver", parsed.componentFiles["wineserver"]?.relativeTo(root)?.path ?: "")
            properties.setProperty("library_dir", parsed.componentFiles["wineLib"]?.relativeTo(root)?.path ?: "")
            properties.setProperty("dxvk_dir", parsed.componentFiles["d3d11"]?.parentFile?.parentFile?.relativeTo(root)?.path ?: "")
            properties.setProperty("turnip_dir", parsed.componentFiles["turnipSo"]?.parentFile?.relativeTo(root)?.path ?: "")
            val profile = manifest.optJSONObject("defaultLaunchProfile")
            if (profile != null) {
                val envProfile = profile.optJSONObject("environment")
                envProfile?.keys()?.forEach { key -> properties.setProperty("profile_env_$key", envProfile.optString(key)) }
            }
        } else {
            runCatching { File(root, "runtime.properties").inputStream().use(properties::load) }
                .onFailure { return LaunchPreparationResult.MissingPayload(listOf("Runtime manifest cannot be read: ${it.message}")) }
        }

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
        val originalIcd = turnipDir?.walkTopDown()?.maxDepth(8)?.firstOrNull { it.isFile && it.name.endsWith(".json", true) && runCatching { it.readText().contains("library_path") }.getOrDefault(false) }
        val turnipLibrary = turnipDir?.walkTopDown()?.maxDepth(8)?.firstOrNull { it.isFile && it.name == "libvulkan_freedreno.so" }
        val icd = if (originalIcd != null && turnipLibrary != null && options.useTurnip) runCatching { rewriteTurnipIcd(originalIcd!!, turnipLibrary!!) }.getOrElse { blockers += "Unable to rewrite Turnip ICD: ${it.message}"; null } else originalIcd
        if (!gameExe.isFile || !isValidX64Pe(gameExe)) blockers += "Game executable is missing or not a valid x64 PE: ${gameExe.absolutePath}"
        if (blockers.isNotEmpty()) return LaunchPreparationResult.MissingPayload(blockers.distinct())

        try {
            val system32 = File(prefix, "drive_c/windows/system32")
            if (!system32.mkdirs() && !system32.isDirectory) throw IOException("Unable to create WINEPREFIX system32.")
            val dxvkDlls = if (options.useDxvk) dxvkDir!! else null
            if (dxvkDlls != null) for (dll in listOf("d3d11.dll", "dxgi.dll")) copyFile(File(dxvkDlls, dll), File(system32, dll))
            for (dll in listOf("xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll", "vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")) {
                val bundleDll = when (dll) {
                    "xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll" -> File(root, "xinput/$dll").takeIf { it.isFile } ?: File(root, "wine/lib/wine/x86_64-windows/$dll")
                    else -> File(root, "wine/lib/wine/x86_64-windows/$dll")
                }
                if (bundleDll.isFile) copyFile(bundleDll, File(system32, dll))
                else if (dll.startsWith("xinput")) {
                    val assetDll = File(context.filesDir, "runtime/$dll")
                    if (assetDll.isFile) copyFile(assetDll, File(system32, dll))
                    else context.assets.open("runtime/$dll").use { input -> File(system32, dll).outputStream().use(input::copyTo) }
                }
            }
            sharedInput.parentFile?.mkdirs()
            if (!sharedInput.exists()) sharedInput.writeBytes(ByteArray(128))
            val zPath = toWineZPath(gameExe)
            val args = WineLaunchCommand.arguments(wine!!.absolutePath, zPath, options.width, options.height)
            val hostLibraryDirectory = turnipLibrary?.parentFile ?: File(context.applicationInfo.nativeLibraryDir ?: context.applicationInfo.dataDir)
            val env = WineLaunchCommand.environment(
                prefix, sharedInput, hostLibraryDirectory, options.fpsCap,
                if (options.useTurnip && turnipDir != null) icd else null, dxvkDlls,
                guestLibraryDirectory = if (manifestFile.isFile) report.libraryDirectory else null,
                guestBinaryDirectory = wine!!.parentFile,
                useDxvk = options.useDxvk
            ).toMutableMap()
            properties.stringPropertyNames().filter { it.startsWith("profile_env_") }.forEach { p ->
                val envKey = p.removePrefix("profile_env_")
                if (envKey.startsWith("BOX64_DYNAREC") || envKey == "WINEDLLOVERRIDES") env[envKey] = properties.getProperty(p)
            }

            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "launcher", "Prepared Wine prefix and launch plan; execution compatibility remains unverified."))
            return LaunchPreparationResult.Prepared(WineLaunchPlan(execCapability.executable ?: box64Candidate, args, env, gameExe.parentFile ?: root, prefix, gameExe))
        } catch (e: Exception) {
            return LaunchPreparationResult.MissingPayload(listOf("Wine prefix preparation failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    fun launch(plan: WineLaunchPlan): ProcessResult {
        if (!inputBridge.isNativeAvailable()) throw IOException("Native MO2X bridge is unavailable; refusing to launch with non-shared fallback input.")
        if (!inputBridge.initialize(sharedInput.absolutePath)) throw IOException("MO2X input bridge initialization failed.")
        return try {
            RuntimeProcess(diagnostics).start(plan.executable, plan.arguments, plan.environment, plan.workingDirectory)
        } catch (error: Exception) {
            runRuntimeCleanupSequence(
                neutralize = { neutralizeInputBridge() },
                stopProcess = { true },
                destroyBridge = { destroyInputBridge() },
                stopServer = { stopWineserver() }
            )
            throw error
        }
    }


    fun neutralizeInputBridge(): Boolean {
        val success = inputBridge.neutralizeAllPlayers()
        if (!success) diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "input-bridge", "One or more players could not be neutralized during cleanup."))
        return success
    }

    fun destroyInputBridge(): Boolean = runCatching {
        inputBridge.destroy()
        true
    }.getOrElse {
        diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "input-bridge", "Bridge destroy failed: ${it.message}"))
        false
    }

    /** Works with both legacy runtime.properties and the current hash-validated JSON manifest. */
    fun stopWineserver(): Boolean {
        val server = WineServerResolver.resolve(bundleRoot)
        if (server == null) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "wineserver", "Cleanup blocked: wineserver path missing, invalid, or outside bundle."))
            return false
        }
        val validated = validator.validate(bundleRoot)
        val box64 = activeBox64Executable?.takeIf { it.isFile && it.canExecute() }
        if (box64 == null || !validated.isReady) {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "wineserver", "Cleanup blocked: extracted nativeLibraryDir Box64 executable is unavailable; refusing filesDir execution."))
            return false
        }
        val environment = buildMap {
            put("WINEPREFIX", prefix.absolutePath)
            put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir ?: context.applicationInfo.dataDir)
            if (File(bundleRoot, "runtime-manifest.json").isFile && validated.libraryDirectory != null) {
                put("BOX64_LD_LIBRARY_PATH", validated.libraryDirectory.absolutePath)
            }
            put("BOX64_PATH", server.parentFile?.absolutePath.orEmpty())
        }
        return runCatching {
            val result = RuntimeProcess(diagnostics).start(box64, listOf(server.absolutePath, "-k"), environment, prefix).waitFor(5000)
            val success = !result.timedOut && result.exitCode == 0
            diagnostics.record(RuntimeDiagnostic(if (success) RuntimeDiagnostic.Severity.INFO else RuntimeDiagnostic.Severity.WARNING, "wineserver", "Cleanup command exit=${result.exitCode}, timedOut=${result.timedOut}."))
            success
        }.getOrElse {
            diagnostics.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.WARNING, "wineserver", "Cleanup failed: ${it.message}"))
            false
        }
    }

    fun stop(result: ProcessResult, graceMs: Long = 1200): Boolean {
        val cleanupSucceeded = runRuntimeCleanupSequence(
            neutralize = { neutralizeInputBridge() },
            stopProcess = { result.stop(graceMs).let { !result.process.isAlive } },
            destroyBridge = { destroyInputBridge() },
            stopServer = { stopWineserver() }
        )
        diagnostics.record(RuntimeDiagnostic(
            if (cleanupSucceeded) RuntimeDiagnostic.Severity.INFO else RuntimeDiagnostic.Severity.ERROR,
            "launcher",
            "Runtime teardown finished; cleanupSucceeded=$cleanupSucceeded."
        ))
        return cleanupSucceeded
    }

    internal fun rewriteTurnipIcd(source: File, library: File): File {
        val sessionDir = File(context.filesDir, "runtime-session").apply { mkdirs() }
        return TurnipIcdConfig.rewrite(source, library, File(sessionDir, "freedreno_icd.aarch64.json"))
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

    private fun isValidX64Pe(file: File): Boolean =
        RuntimeBinaryInspector.matches(file, RuntimeBinaryInspector.PE32_PLUS_X86_64)
}

/** Cleanup steps are ordered and failure-isolated so later resources are still released. */
internal fun runRuntimeCleanupSequence(neutralize: () -> Boolean, stopProcess: () -> Boolean, destroyBridge: () -> Boolean, stopServer: () -> Boolean): Boolean {
    var success = true
    listOf(neutralize, stopProcess, destroyBridge, stopServer).forEachIndexed { index, step ->
        val stepSuccess = runCatching { step() }.getOrDefault(false)
        if (!stepSuccess) success = false
    }
    return success
}
