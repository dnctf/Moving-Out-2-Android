package com.dnctf.movingout2

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.app.Dialog
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.CheckBox
import android.widget.Button
import android.widget.ScrollView
import com.dnctf.movingout2.runtime.RuntimeDiagnostic
import com.dnctf.movingout2.runtime.RuntimeDiagnostics
import com.dnctf.movingout2.compatibility.DeviceDiagnosticsProvider
import com.dnctf.movingout2.input.RuntimeInputBridge
import com.dnctf.movingout2.input.ControllerInputCoordinator
import com.dnctf.movingout2.input.VirtualControllerView
import com.dnctf.movingout2.input.PhysicalGamepadManager
import com.dnctf.movingout2.input.ControllerProfile
import com.dnctf.movingout2.input.ControllerProfileRepository
import com.dnctf.movingout2.input.controllerProfileStorageKey
import com.dnctf.movingout2.input.readBoundedUtf8Profile
import com.dnctf.movingout2.installation.GameInstallationManager
import com.dnctf.movingout2.installation.InstallationRepository
import com.dnctf.movingout2.runtime.RuntimeBundleImporter
import com.dnctf.movingout2.runtime.RuntimeLaunchConfig
import com.dnctf.movingout2.graphics.VulkanCapabilities
import com.dnctf.movingout2.runtime.RuntimeBundleValidator
import com.dnctf.movingout2.runtime.RuntimeInstallationState
import com.dnctf.movingout2.runtime.AndroidExecCapabilityProbe
import com.dnctf.movingout2.runtime.WineSessionSupervisor
import com.dnctf.movingout2.runtime.WineSessionState
import com.dnctf.movingout2.runtime.RuntimeGraphicsComponents
import com.dnctf.movingout2.settings.LauncherSettings
import com.dnctf.movingout2.ui.home.LauncherHomeView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var graphicsStatus: TextView
    private lateinit var playButton: Button
    @Volatile private var activeWineSession: WineSessionSupervisor? = null
    private lateinit var repository: InstallationRepository
    private lateinit var gameRegistry: com.dnctf.movingout2.installation.GameRegistry
    private lateinit var physicalGamepadManager: PhysicalGamepadManager
    private val inputBridge = RuntimeInputBridge()
    private val runtimeInstallInProgress = AtomicBoolean(false)
    private val gameImportInProgress = AtomicBoolean(false)
    private val launchReadinessCoordinator = com.dnctf.movingout2.runtime.LaunchReadinessCoordinator()
    @Volatile private var vulkanAvailable = false
    @Volatile private var graphicsProbeComplete = false
    @Volatile private var lastLaunchReady = false
    private val diagnosticLog = RuntimeDiagnostics()
    private var pendingDiagnosticZip: File? = null
    private val pickDiagnosticDestination = 1004
    private lateinit var inputCoordinator: ControllerInputCoordinator
    private var controllerProfile = ControllerProfile.presets.first()
    private var controllerProfileGameKey = "moving-out-2"
    private var pendingProfileExport: String? = null
    private val pickFolder = 1001
    private val pickRuntimeBundle = 1002
    private val pickGameZip = 1003
    private val pickRuntimeZip = 1007
    private val pickControllerProfileImport = 1005
    private val pickControllerProfileExport = 1006

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("auto_launch_triggered") == true) launchReadinessCoordinator.markAutoLaunched()
        // Render a usable launcher shell before touching native/JNI or persisted repositories.
        val views = LauncherHomeView.create(
            this, ::openFolderPicker, ::revalidateSavedFolder,
            ::openRuntimeZipPicker, ::openRuntimeFolderPicker,
            ::downloadRuntimeFromRepository, ::openGameZipPicker, ::showSettings,
            ::showControllerTest, ::exportDiagnostics, ::startGameSession
        )
        status = views.status
        runtimeStatus = views.runtimeStatus
        graphicsStatus = views.graphicsStatus
        playButton = views.playButton
        setContentView(views.root)
        status.text = "Starting launcher…"
        runtimeStatus.text = "Checking runtime installation…"
        graphicsStatus.text = "Device diagnostics are starting…"
        try {
            repository = InstallationRepository(this)
            gameRegistry = com.dnctf.movingout2.installation.GameRegistry(this, repository)
            val activeGame = gameRegistry.getActiveGame()
            controllerProfileGameKey = controllerProfileStorageKey(activeGame?.id)
            val storedProfile = ControllerProfileRepository.load(this, controllerProfileGameKey)
            val legacyProfile = if (controllerProfileGameKey == "moving-out-2") null
                else ControllerProfileRepository.load(this, "moving-out-2")
            controllerProfile = storedProfile ?: legacyProfile ?: ControllerProfile.presets.first()
            if (storedProfile == null && legacyProfile != null) ControllerProfileRepository.save(this, controllerProfileGameKey, legacyProfile)
            runCatching { inputBridge.initialize(RuntimeLaunchConfig(filesDir).sharedMemoryFile.absolutePath) }
                .onFailure { error -> diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "input-bridge-init", "${error.javaClass.name}: ${error.message ?: "no message"}")) }
            inputCoordinator = ControllerInputCoordinator(inputBridge, controllerProfile)
            physicalGamepadManager = PhysicalGamepadManager(
                this,
                onDeviceState = { deviceId, _, state, connected ->
                    if (::inputCoordinator.isInitialized) inputCoordinator.updatePhysicalDevice(deviceId, state, connected)
                },
                onAggregateState = { /* Per-device routing owns XInput slots. */ }
            )
            val activeAtStartup = gameRegistry.getActiveGame()
            status.text = if (activeAtStartup?.source == com.dnctf.movingout2.core.model.InstallationSource.SAF_DIRECTORY) {
                "Saved SAF game folder found. Importing automatically into app storage…"
            } else if (activeAtStartup != null) {
                "Imported game installation found. Refreshing launch readiness…"
            } else if (repository.savedUri() != null) {
                "Saved game folder found. Checking access and importing automatically…"
            } else "Import the game ZIP or select an existing game folder."
            if (activeAtStartup?.source == com.dnctf.movingout2.core.model.InstallationSource.SAF_DIRECTORY) {
                startSafGameImport(Uri.parse(activeAtStartup.location))
            } else if (activeAtStartup == null) {
                repository.savedUri()?.let(::startSafGameImport)
            }
            refreshRuntimeStatus()
            probeGraphicsAsync()
            ensureRuntimeInstalledOnFirstLaunch()
        } catch (error: Throwable) {
            val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
            diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "launcher-onCreate",
                "${error.javaClass.name}: ${error.message ?: "no message"}\n$trace"))
            status.text = "Launcher initialization failed safely. UI is available, but a subsystem could not start.\n${error.javaClass.simpleName}: ${error.message ?: "no message"}\nExport diagnostics and share the report."
            runtimeStatus.text = "Runtime setup is paused until launcher initialization is repaired."
            graphicsStatus.text = "Device probe was not started."
            Toast.makeText(this, "Launcher initialization failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun activateControllerProfileForGame(game: com.dnctf.movingout2.installation.RegisteredGame?) {
        val nextKey = controllerProfileStorageKey(game?.id)
        if (nextKey == controllerProfileGameKey) return
        val stored = ControllerProfileRepository.load(this, nextKey)
        val legacy = if (nextKey == "moving-out-2") null
            else ControllerProfileRepository.load(this, "moving-out-2")
        val nextProfile = stored ?: legacy ?: ControllerProfile.presets.first()
        if (stored == null && legacy != null) ControllerProfileRepository.save(this, nextKey, nextProfile)
        controllerProfileGameKey = nextKey
        controllerProfile = nextProfile
        inputCoordinator.setProfile(nextProfile)
    }

    private fun exportDiagnostics() {
        Toast.makeText(this, "Preparing diagnostics ZIP…", Toast.LENGTH_SHORT).show()
        com.dnctf.movingout2.core.logging.DiagnosticExportCoordinator(this, repository, diagnosticLog)
            .exportAsync { result ->
                result.onSuccess { file ->
                    pendingDiagnosticZip = file
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                        putExtra(Intent.EXTRA_TITLE, file.name)
                    }, pickDiagnosticDestination)
                }.onFailure {
                    Toast.makeText(this, "Diagnostic export failed: " + it.message, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun showControllerTest() {
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 12) }
        val info = TextView(this).apply { text = "Touch the controls or connect a controller.\nMO2X: " + if (inputBridge.isNativeAvailable() && inputBridge.isInitialized()) "native shared memory ready; Wine reader pending" else if (inputBridge.isInitialized()) "JVM fallback active; native shared memory unavailable" else "transport initialization failed"; textSize = 14f }
        val stateText = TextView(this).apply { text = "Input: idle"; textSize = 13f; setPadding(0, 8, 0, 8) }
        val pad = VirtualControllerView(this, controllerProfile.deadzone) { state ->
            inputCoordinator.updateTouchState(state)
            val readback = inputCoordinator.previewPacket(state).serializeLittleEndian().joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            stateText.text = "Input: buttons=" + state.buttons.joinToString() + " | left=(" + state.leftX + ", " + state.leftY + ") | right=(" + state.rightX + ", " + state.rightY + ")\nMO2X packet: " + readback
        }
        val overlay = LauncherSettings.load(this)
        pad.configure(overlay.controllerScale / 100f, overlay.controllerOpacity / 100f, (overlay.controllerOffsetX - 50) / 100f, (overlay.controllerOffsetY - 50) / 100f)
        pad.setHapticsEnabled(overlay.controllerHaptics)
        pad.setOverlayVisible(overlay.controllerEnabled)
        container.addView(info)
        container.addView(stateText)
        val padHeight = (resources.displayMetrics.heightPixels * .52f).toInt().coerceIn(260, 520)
        container.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, padHeight))
        dialog.setContentView(container)
        dialog.setTitle("Controller test")
        dialog.setOnDismissListener {
            inputCoordinator.clearTouch()
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.96f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0) {
            if (::physicalGamepadManager.isInitialized) physicalGamepadManager.handleKey(event)?.let { return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0) {
            if (::physicalGamepadManager.isInitialized) physicalGamepadManager.handleMotion(event)?.let { return true }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (::inputCoordinator.isInitialized) inputCoordinator.resume()
        if (::physicalGamepadManager.isInitialized) physicalGamepadManager.register()
    }

    override fun onPause() {
        // Disable coordinator callbacks first: unregister() emits a final neutral device state.
        // That callback must not reconnect the XInput slot while the Activity is pausing.
        if (::inputCoordinator.isInitialized) inputCoordinator.resetAndDisconnect()
        if (::physicalGamepadManager.isInitialized) physicalGamepadManager.unregister()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("auto_launch_triggered", launchReadinessCoordinator.hasAutoLaunched())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        activeWineSession?.let { session ->
            Thread({ session.shutdownAll() }, "wine-session-destroy").apply { isDaemon = true; start() }
        }
        if (::inputCoordinator.isInitialized) inputCoordinator.destroy()
        super.onDestroy()
    }

    private fun openControllerProfileImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, pickControllerProfileImport)
    }

    private fun openControllerProfileExport() {
        pendingProfileExport = controllerProfile.exportToJson()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "moving-out-2-controller-profile.json")
        }, pickControllerProfileExport)
    }

    private fun startSafGameImport(uri: Uri) {
        if (!gameImportInProgress.compareAndSet(false, true)) return
        val progress = ProgressDialog(this).apply {
            setTitle("Importing game folder")
            setMessage("Copying SAF files into private app storage…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMax(100)
            setProgress(0)
            setCancelable(false)
            show()
        }
        Thread({
            val result = runCatching {
                com.dnctf.movingout2.installation.SafGameImporter(this).import(uri, onProgress = { update ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            progress.progress = update.percent
                            progress.setMessage("Copying game files: ${update.percent}% (${update.copiedFiles} files, ${update.copiedBytes / (1024 * 1024)} MiB)")
                        }
                    }
                })
            }
            gameImportInProgress.set(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                result.onSuccess { imported ->
                    val activeGame = gameRegistry.registerImportedZip(imported.gameRoot)
                    activateControllerProfileForGame(activeGame)
                    status.text = "SAF game folder imported automatically to ${imported.gameRoot.absolutePath}.\nFiles: ${imported.copiedFiles} | Copied: ${imported.copiedBytes / (1024 * 1024)} MiB\nGame validation: ${activeGame.validationStatus}"
                    refreshRuntimeStatus()
                    Toast.makeText(this, "Game files copied and validated in app storage.", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    status.text = "Automatic SAF import failed safely: ${error.message ?: error.javaClass.simpleName}\nThe original SAF folder was not modified. Check free storage and permissions."
                    diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "saf-game-import", status.text.toString()))
                    Toast.makeText(this, "Game folder import failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "saf-game-import").apply { isDaemon = true; start() }
    }

    private fun openFolderPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, pickFolder)
    }

    private fun openRuntimeZipPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, pickRuntimeZip)
    }

    private fun openRuntimeFolderPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, pickRuntimeBundle)
    }

    private fun ensureRuntimeInstalledOnFirstLaunch() {
        val report = RuntimeBundleValidator().validate(File(filesDir, "native_runtime/bundle"))
        if (report.isReady) return
        if (android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            runtimeStatus.text = "Automatic setup stopped: this app requires an ARM64 (arm64-v8a) Android device."
            return
        }
        runtimeStatus.text = "First-run setup: runtime missing. Preparing automatic download…"
        startRuntimeRepositoryInstall(automatic = true)
    }

    private fun downloadRuntimeFromRepository() {
        startRuntimeRepositoryInstall(automatic = false)
    }

    private fun startRuntimeRepositoryInstall(automatic: Boolean) {
        if (!runtimeInstallInProgress.compareAndSet(false, true)) {
            runtimeStatus.text = "Runtime setup is already running. Please wait for it to finish."
            return
        }
        runtimeStatus.text = if (automatic) {
            "First-run setup: checking device and runtime release…"
        } else {
            "Runtime repair: checking device and GitHub Releases…"
        }
        Thread({
            val result = runCatching {
                val device = DeviceDiagnosticsProvider.collect(this)
                if (device.abi.none { it == "arm64-v8a" }) {
                    throw IllegalStateException("Unsupported CPU ABI. This runtime requires arm64-v8a.")
                }
                com.dnctf.movingout2.runtime.RuntimeRepositoryInstaller(this, diagnostics = diagnosticLog).installLatest { message ->
                    runOnUiThread { if (!isFinishing && !isDestroyed) runtimeStatus.text = message }
                }
            }
            runtimeInstallInProgress.set(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { installed ->
                    refreshRuntimeStatus()
                    runtimeStatus.append("\nRuntime release: ${installed.version}\nFiles: ${installed.files} | Extracted: ${installed.bytes / (1024 * 1024)} MiB\nIntegrity: SHA-256 + manifest + ABI validation passed.")
                    Toast.makeText(this, "Runtime setup completed and verified.", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    runtimeStatus.text = "Runtime setup failed safely: ${error.message ?: error.javaClass.simpleName}\nExisting runtime was preserved. Check free storage and network, then tap Repair runtime."
                    Toast.makeText(this, "Runtime setup failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "runtime-repository-installer").apply { isDaemon = false; start() }
    }

    private fun openGameZipPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, pickGameZip)
    }

    private fun revalidateSavedFolder() {
        val active = gameRegistry.getActiveGame()
        activateControllerProfileForGame(active)
        status.text = if (active == null) {
            val uri = repository.savedUri()
            if (uri == null) "No installation folder saved." else runCatching {
                formatReport(GameInstallationManager(this).validate(uri))
            }.getOrElse { "Folder access error: ${it.message ?: "permission unavailable"}" }
        } else {
            buildString {
                append("Active installation: ").append(active.displayName)
                append("\nStatus: ").append(active.validationStatus)
                append("\nSource: ").append(active.source)
                append("\nArchitecture: ").append(active.architecture ?: "unknown")
                if (active.unityVersion != null) append("\nUnity: ").append(active.unityVersion)
                if (active.metadataVersion != null) append("\nIL2CPP metadata: v").append(active.metadataVersion)
                if (active.missingFiles.isNotEmpty()) append("\nMissing:\n- ").append(active.missingFiles.joinToString("\n- "))
                if (active.accessErrors.isNotEmpty()) append("\nAccess errors:\n- ").append(active.accessErrors.joinToString("\n- "))
                append("\n\nReady to launch: ").append(if (active.isReadyToLaunch) "YES (runtime integration pending)" else "NO")
            }
        }
        refreshRuntimeStatus()
    }

    private fun showSettings() {
        val current = LauncherSettings.load(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 8) }
        val scroll = ScrollView(this).apply { addView(panel) }
        var dialog: AlertDialog? = null
        fun slider(label: String, value: Int, max: Int = 100): SeekBar {
            val labelView = TextView(this).apply { text = "$label: $value%"; textSize = 16f }
            panel.addView(labelView)
            val bar = SeekBar(this).apply { this.max = max; progress = value.coerceIn(0, max) }
            panel.addView(bar)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { labelView.text = "$label: $progress%" }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            return bar
        }
        val resolution = slider("Resolution scale", current.resolutionScale)
        val master = slider("Master volume", current.masterVolume)
        val music = slider("Music volume", current.musicVolume)
        val sfx = slider("SFX volume", current.sfxVolume)
        val controllerScale = slider("Controller size (%)", current.controllerScale, 150)
        val controllerOpacity = slider("Controller opacity (%)", current.controllerOpacity)
        val controllerOffsetX = slider("Controller horizontal position (35–65)", current.controllerOffsetX)
        val controllerOffsetY = slider("Controller vertical position (35–65)", current.controllerOffsetY)
        val controllerEnabled = CheckBox(this).apply { text = "Show virtual controller"; isChecked = current.controllerEnabled }
        val controllerHaptics = CheckBox(this).apply { text = "Touch haptics"; isChecked = current.controllerHaptics }
        panel.addView(controllerEnabled); panel.addView(controllerHaptics)
        panel.addView(TextView(this).apply { text = "Controller profile"; textSize = 16f })
        val profileOptions = ControllerProfile.presets.map { it.name } + "Custom (keep current)"
        val profileSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, profileOptions)
            val presetIndex = ControllerProfile.presets.indexOfFirst { it.name == controllerProfile.name }
            setSelection(if (presetIndex >= 0) presetIndex else profileOptions.lastIndex)
        }
        panel.addView(profileSpinner)
        val profileImportButton = Button(this).apply { text = "Import profile JSON"; setOnClickListener { dialog?.dismiss(); openControllerProfileImport() } }
        val profileExportButton = Button(this).apply { text = "Export current profile JSON"; setOnClickListener { dialog?.dismiss(); openControllerProfileExport() } }
        panel.addView(profileImportButton); panel.addView(profileExportButton)

        panel.addView(TextView(this).apply { text = "FPS cap target"; textSize = 16f })
        val fpsOptions = listOf("30 FPS (recommended minimum)", "45 FPS", "60 FPS")
        val fps = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
            setSelection(listOf(30, 45, 60).indexOf(current.fpsTarget).coerceAtLeast(0))
        }
        panel.addView(fps)
        panel.addView(TextView(this).apply { text = "Graphics preset"; textSize = 16f })
        val presetOptions = listOf("Low / Snapdragon 855", "Balanced", "High / newer Snapdragon")
        val preset = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presetOptions)
            setSelection(presetOptions.indexOf(current.graphicsPreset).coerceAtLeast(0))
        }
        panel.addView(preset)
        val builder = AlertDialog.Builder(this).setTitle("Performance & audio")
            .setMessage("Baseline target: Snapdragon 855+, Low graphics, 70–75% resolution scale, 30 FPS. Saved values are preferences only; runtime application is not implemented yet.")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val selectedFps = listOf(30, 45, 60)[fps.selectedItemPosition.coerceIn(0, 2)]
                val selectedPreset = presetOptions[preset.selectedItemPosition.coerceIn(0, presetOptions.lastIndex)]
                val selectedProfileIndex = profileSpinner.selectedItemPosition
                if (selectedProfileIndex in ControllerProfile.presets.indices) {
                    controllerProfile = ControllerProfile.presets[selectedProfileIndex]
                    inputCoordinator.setProfile(controllerProfile)
                    ControllerProfileRepository.save(this, controllerProfileGameKey, controllerProfile)
                }
                LauncherSettings.save(this, LauncherSettings(resolutionScale = resolution.progress.coerceIn(50, 100), fpsTarget = selectedFps, graphicsPreset = selectedPreset, masterVolume = master.progress, musicVolume = music.progress, sfxVolume = sfx.progress, controllerScale = controllerScale.progress.coerceIn(65, 150), controllerOpacity = controllerOpacity.progress.coerceIn(15, 100), controllerOffsetX = controllerOffsetX.progress.coerceIn(35, 65), controllerOffsetY = controllerOffsetY.progress.coerceIn(35, 65), controllerEnabled = controllerEnabled.isChecked, controllerHaptics = controllerHaptics.isChecked))
                Toast.makeText(this, "Settings saved (runtime application pending).", Toast.LENGTH_SHORT).show()
            }
        dialog = builder.create()
        dialog?.show()
    }


    private fun probeGraphicsAsync() {
        graphicsProbeComplete = false
        graphicsStatus.text = "Probing Vulkan loader and physical device..."
        Thread({
            val device = runCatching { DeviceDiagnosticsProvider.collect(this) }
            val result = runCatching { VulkanCapabilities.probe(this) }
            vulkanAvailable = result.getOrNull()?.available == true
            graphicsProbeComplete = true
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val deviceText = device.getOrNull()?.let {
                    "Device: ${it.manufacturer} ${it.model}\nSoC: ${it.soc}\nCPU ABI: ${it.abi.joinToString()}\nRAM: ${it.ramBytes?.div(1024 * 1024) ?: "unknown"} MiB (free ${it.availableRamBytes?.div(1024 * 1024) ?: "unknown"} MiB)\nGPU family hint: ${it.gpuVendorHint}"
                } ?: "Device detection unavailable."
                graphicsStatus.text = result.fold({ probe ->
                    buildString {
                        append(deviceText)
                        append("\n\nActive Vulkan driver: ").append(probe.nativeProbeStatus ?: "unavailable")
                        append("\nVulkan API: ").append(probe.apiVersion ?: "unknown")
                        append("\nVulkan driver version (raw): ").append(probe.driverVersionRaw ?: "unavailable")
                        append("\nGPU: ").append(probe.gpuVendor ?: "unknown").append(" / ").append(probe.gpuName ?: "unknown")
                        append("\nDevice extensions: ").append(probe.extensions.size)
                        append("\nDriver policy: use the Android system Vulkan driver; do not force-load Turnip unless compatibility is verified.")
                        if (probe.blockers.isNotEmpty()) append("\n\nNotes:\n- ").append(probe.blockers.joinToString("\n- "))
                    }
                }, { deviceText + "\n\nVulkan probe failed: ${it.message ?: it.javaClass.simpleName}" })
                refreshRuntimeStatus()
            }
        }, "graphics-probe").apply { isDaemon = true; start() }
    }

    private fun refreshRuntimeStatus() {
        val root = File(filesDir, "native_runtime/bundle")
        val report = RuntimeBundleValidator().validate(root)
        val graphics = RuntimeGraphicsComponents.assess(root)
        if (!report.isReady || report.box64 == null) {
            lastLaunchReady = false
            playButton.isEnabled = false
            playButton.alpha = 0.55f
            runtimeStatus.text = "Runtime bundle: ${report.status}\nLaunch readiness: BLOCKED (runtime not validated)\nDXVK: ${graphics.dxvk}\nTurnip: ${graphics.turnip}\n${report.blockers.joinToString("\n")}\n${graphics.notes.joinToString("\n")}"
            return
        }
        lastLaunchReady = false
        playButton.isEnabled = false
        playButton.alpha = 0.55f
        runtimeStatus.text = "Runtime bundle: READY (checking nativeLibraryDir exec capability and Vulkan)…"
        Thread({
            val exec = runCatching { AndroidExecCapabilityProbe(this, diagnosticLog).probe(report.box64) }
            val active = runCatching { gameRegistry.getActiveGame() }.getOrNull()
            val gamePresent = active?.isReadyToLaunch == true &&
                active.source == com.dnctf.movingout2.core.model.InstallationSource.INTERNAL_IMPORTED &&
                active.executableFile?.isFile == true &&
                (active.executableFile?.canonicalFile?.toPath()?.startsWith(File(filesDir, "game_data").canonicalFile.toPath()) == true ||
                    active.executableFile?.canonicalFile?.toPath()?.startsWith(File(filesDir, "game_import").canonicalFile.toPath()) == true)
            val dxvkReady = graphics.dxvk == "FILES_PRESENT_UNVERIFIED"
            val snapshot = com.dnctf.movingout2.runtime.LaunchReadinessSnapshot(
                gameImportedToAppStorage = gamePresent,
                runtimeBundleReady = report.isReady && dxvkReady,
                execProbeAllowed = exec.getOrNull()?.allowed == true,
                vulkanAvailable = vulkanAvailable,
                refreshComplete = graphicsProbeComplete
            )
            val decision = launchReadinessCoordinator.evaluate(snapshot)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastLaunchReady = decision.playEnabled
                val running = activeWineSession?.state == WineSessionState.RUNNING
                playButton.isEnabled = decision.playEnabled || running
                playButton.alpha = if (playButton.isEnabled) 1f else 0.55f
                val execText = exec.getOrNull()?.let { "${it.capability}: ${it.detail}" }
                    ?: "EXEC_PROBE_FAILED: ${exec.exceptionOrNull()?.message}"
                val gameText = when {
                    active == null -> "Game: NOT_SELECTED"
                    active.source != com.dnctf.movingout2.core.model.InstallationSource.INTERNAL_IMPORTED ->
                        "Game: FILES_PRESENT (automatic SAF import pending)"
                    gamePresent -> "Game: IMPORTED_TO_APP_STORAGE"
                    else -> "Game: ${active.validationStatus}"
                }
                runtimeStatus.text = "Runtime bundle: READY\nDXVK: ${graphics.dxvk}\nTurnip: ${graphics.turnip}\nBox64 execution: ${if (snapshot.execProbeAllowed) "ALLOWED" else "BLOCKED"}\n${execText}\n${gameText}\nVulkan: ${if (vulkanAvailable) "AVAILABLE" else if (graphicsProbeComplete) "UNAVAILABLE" else "CHECKING"}\nLaunch readiness: ${decision.readiness}\n${graphics.notes.joinToString("\n")}"
                if (decision.shouldAutoLaunch && !running) {
                    diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO, "auto-launch", "All game/runtime/exec/Vulkan checks passed; triggering one automatic launch for this Activity session."))
                    playButton.post { if (!isFinishing && !isDestroyed) startGameSession() }
                }
            }
        }, "runtime-launch-readiness").apply { isDaemon = true; start() }
    }

    private fun startGameSession() {
        val existing = activeWineSession
        if (existing != null && existing.state == WineSessionState.RUNNING) {
            Thread({
                existing.shutdownAll()
                activeWineSession = null
                runOnUiThread {
                    playButton.text = "Play Moving Out 2"
                    refreshRuntimeStatus()
                    Toast.makeText(this, "Game session stopped.", Toast.LENGTH_LONG).show()
                }
            }, "wine-session-stop").apply { isDaemon = true; start() }
            return
        }
        val active = runCatching { gameRegistry.getActiveGame() }.getOrNull()
        if (active == null || !active.isReadyToLaunch) {
            Toast.makeText(this, "Import and validate Moving Out 2 game files first.", Toast.LENGTH_LONG).show()
            refreshRuntimeStatus()
            return
        }
        if (active.source != com.dnctf.movingout2.core.model.InstallationSource.INTERNAL_IMPORTED) {
            Toast.makeText(this, "This SAF folder is validated but not launchable yet. Import the game ZIP into app storage.", Toast.LENGTH_LONG).show()
            return
        }
        val gameExe = active.executableFile
        if (gameExe == null || !gameExe.isFile) {
            Toast.makeText(this, "The validated game executable could not be resolved.", Toast.LENGTH_LONG).show()
            return
        }
        val root = File(filesDir, "native_runtime/bundle")
        playButton.isEnabled = false
        runtimeStatus.text = "Starting Box64 / Wine session…"
        val supervisor = WineSessionSupervisor(
            this, root, "moving-out-2", diagnosticLog,
            onSessionFinished = {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        activeWineSession = null
                        playButton.text = "Play Moving Out 2"
                        playButton.isEnabled = lastLaunchReady
                        playButton.alpha = if (lastLaunchReady) 1f else 0.55f
                        runtimeStatus.text = "Game process exited. Manual relaunch is available when readiness remains READY."
                        refreshRuntimeStatus()
                    }
                }
            }
        )
        Thread({
            val started = runCatching { supervisor.startSession(gameExe) }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    supervisor.shutdownAll()
                    return@runOnUiThread
                }
                if (started.getOrDefault(false)) {
                    activeWineSession = supervisor
                    playButton.text = "Stop Game Session"
                    playButton.isEnabled = true
                    playButton.alpha = 1f
                    runtimeStatus.text = "Game session: RUNNING\nBox64 executable: ${File(applicationInfo.nativeLibraryDir, "libbox64.so").absolutePath}\nWine session logs: files/diagnostics/wine_session.log and runtime-process.log"
                    Toast.makeText(this, "Box64 / Wine game session started.", Toast.LENGTH_LONG).show()
                } else {
                    activeWineSession = null
                    playButton.text = "Play Moving Out 2"
                    runtimeStatus.text = "Game session failed during preflight/startup. Export diagnostics and inspect wine_session.log."
                    refreshRuntimeStatus()
                    Toast.makeText(this, "Game session could not start. Export diagnostics.", Toast.LENGTH_LONG).show()
                }
            }
        }, "wine-session-start").apply { isDaemon = true; start() }
    }

    @Deprecated("Use Activity Result API in a later refactor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        if (requestCode == pickDiagnosticDestination) {
            val destination = data?.data ?: return
            val source = pendingDiagnosticZip ?: return
            runCatching { contentResolver.openOutputStream(destination)?.use { out -> source.inputStream().use { input -> input.copyTo(out) } } ?: error("Destination stream unavailable") }
                .onSuccess { Toast.makeText(this,"Diagnostics ZIP saved.",Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this,"Saving diagnostics failed: " + it.message,Toast.LENGTH_LONG).show() }
            pendingDiagnosticZip=null
            return
        }
        when (requestCode) {
            pickControllerProfileImport -> runCatching {
                val raw = contentResolver.openInputStream(uri)?.use { stream ->
                    readBoundedUtf8Profile(stream)
                } ?: error("Profile file could not be opened.")
                val imported = ControllerProfileRepository.importFromJson(raw)
                ControllerProfileRepository.save(this, controllerProfileGameKey, imported)
                controllerProfile = imported
                inputCoordinator.setProfile(imported)
                Toast.makeText(this, "Controller profile imported: ${imported.name}", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this, "Profile import rejected: ${it.message}", Toast.LENGTH_LONG).show() }
            pickControllerProfileExport -> runCatching {
                val json = pendingProfileExport ?: error("No controller profile prepared.")
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("Destination stream unavailable.")
                Toast.makeText(this, "Controller profile exported.", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(this, "Profile export failed: ${it.message}", Toast.LENGTH_LONG).show() }
                .also { pendingProfileExport = null }
            pickFolder -> try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val activeGame = gameRegistry.registerSafFolder(uri)
                activateControllerProfileForGame(activeGame)
                status.text = formatReport(GameInstallationManager(this).validate(uri)) + "\n\nAutomatic copy into app storage is starting…"
                startSafGameImport(uri)
            } catch (e: Exception) { status.text = "Folder import failed safely: ${e.message ?: e.javaClass.simpleName}" }
            pickRuntimeZip -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (!runtimeInstallInProgress.compareAndSet(false, true)) {
                    runtimeStatus.text = "Another runtime operation is already running."
                    return
                }
                runtimeStatus.text = "Importing runtime ZIP into private app storage…"
                Thread({
                    val result = runCatching { RuntimeBundleImporter(this).importZip(uri) }
                    runtimeInstallInProgress.set(false)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        result.onSuccess { imported ->
                            val graphics = RuntimeGraphicsComponents.assess(File(filesDir, "native_runtime/bundle"))
                            runtimeStatus.text = "Runtime ZIP imported and extracted successfully.\nFiles: ${imported.copiedFiles} | Expanded: ${imported.copiedBytes / (1024 * 1024)} MiB\nManifest: schema v2 | ABI: ARM64 | Integrity: VALIDATED\nDXVK: ${graphics.dxvk}\n${graphics.notes.joinToString("\n")}"
                            refreshRuntimeStatus()
                            Toast.makeText(this, "Runtime ZIP installed and validated.", Toast.LENGTH_LONG).show()
                        }.onFailure { error ->
                            val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
                            diagnosticLog.record(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR, "runtime-zip-import",
                                "${error.javaClass.name}: ${error.message ?: "no message"}\n$trace"))
                            runtimeStatus.text = "Runtime ZIP import failed. Existing runtime was preserved when possible.\n${error.javaClass.simpleName}: ${error.message ?: "no message"}"
                            Toast.makeText(this, "Runtime ZIP import failed: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }, "runtime-zip-importer").apply { isDaemon = false; start() }
            }
            pickRuntimeBundle -> try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val imported = RuntimeBundleImporter(this).importTree(uri)
                val graphics = RuntimeGraphicsComponents.assess(File(filesDir, "native_runtime/bundle"))
                runtimeStatus.text = "Runtime bundle: STRUCTURE_VALIDATED\nCopied ${imported.copiedFiles} files (${imported.copiedBytes} bytes).\nDXVK: ${graphics.dxvk}\nTurnip: ${graphics.turnip}\n${graphics.notes.joinToString("\n")}\nExecution/driver loading not verified; launch remains blocked."
                refreshRuntimeStatus()
            } catch (e: Exception) { runtimeStatus.text = "Runtime bundle import failed: ${e.message ?: e.javaClass.simpleName}\nExisting bundle is preserved when possible." }
            pickGameZip -> {
                val cancelled = AtomicBoolean(false)
                val progressDialog = ProgressDialog(this).apply {
                    setTitle("Importing game ZIP")
                    setMessage("Preparing safe import…")
                    setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                    setMax(100)
                    setProgress(0)
                    setCancelable(false)
                    setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "Cancel") { _, _ -> cancelled.set(true); setMessage("Cancelling safely…") }
                }
                progressDialog.show()
                com.dnctf.movingout2.installation.GameZipImportCoordinator(this, repository)
                    .importAsync(uri, cancelled = { cancelled.get() }, onProgress = { progress ->
                        progressDialog.setProgress(progress.percent)
                        val mibDone = progress.bytesDone / (1024 * 1024)
                        val mibTotal = progress.totalBytes / (1024 * 1024)
                        progressDialog.setMessage("${progress.phase}: ${progress.percent}% (${progress.entriesDone}/${progress.totalEntries} entries, ${mibDone}/${mibTotal} MiB)")
                    }) { result ->
                        progressDialog.dismiss()
                        status.text = result.fold({ summary ->
                            val activeGame = gameRegistry.registerImportedZip(summary.imported.gameRoot)
                            activateControllerProfileForGame(activeGame)
                            buildString {
                                append("ZIP import successful. Files: ").append(summary.imported.fileCount)
                                append("\nExpanded: ").append(summary.imported.bytesWritten / (1024 * 1024)).append(" MiB")
                                append("\nImported game root: ").append(summary.imported.gameRoot.absolutePath)
                                append("\n\nGame validation: ").append(summary.validation.status)
                                summary.validation.stages.forEach { stage ->
                                    append("\n").append(stage.stage).append(": ").append(if (stage.passed) "PASS" else "FAIL")
                                }
                                append("\nRuntime readiness: shown in the Runtime panel")
                            }
                        }, { "ZIP import failed safely: ${it.message ?: it.javaClass.simpleName}" })
                        refreshRuntimeStatus()
                    }
            }
        }
    }

    private fun formatReport(report: com.dnctf.movingout2.installation.SafInstallationReport): String =
        buildString {
            append(if (report.isComplete) "Installation: FILES_PRESENT" else "Installation: INCOMPLETE")
            append("\n\n")
            report.stageSummary.forEach { append(it).append('\n') }
            if (report.missing.isNotEmpty()) append("\nMissing:\n- ").append(report.missing.joinToString("\n- "))
            if (report.accessErrors.isNotEmpty()) append("\nAccess errors:\n- ").append(report.accessErrors.joinToString("\n- "))
            if (report.staticImports.isNotEmpty()) {
                append("\n\nPE static imports:")
                report.staticImports.toSortedMap().forEach { (file, imports) ->
                    append("\n").append(file).append(": ")
                    append(if (imports.isEmpty()) "no import directory / no imports" else imports.sorted().joinToString(", "))
                }
            }
            if (report.importErrors.isNotEmpty()) {
                append("\nPE import analysis notes:")
                report.importErrors.toSortedMap().forEach { (file, notes) -> append("\n").append(file).append(": ").append(notes.joinToString("; ")) }
            }
            append("\n\nRuntime readiness: shown in the Runtime panel")
        }
}
