package com.dnctf.movingout2

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.app.Dialog
import android.view.InputDevice
import android.hardware.input.InputManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.Bundle
import android.content.Intent
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import com.dnctf.movingout2.input.GamepadTranslator
import com.dnctf.movingout2.input.RuntimeInputBridge
import com.dnctf.movingout2.input.VirtualControllerView
import com.dnctf.movingout2.installation.GameInstallationManager
import com.dnctf.movingout2.installation.GameZipImporter
import com.dnctf.movingout2.installation.InstallationRepository
import com.dnctf.movingout2.runtime.RuntimeBundleImporter
import com.dnctf.movingout2.runtime.RuntimeLaunchConfig
import com.dnctf.movingout2.graphics.VulkanCapabilities
import com.dnctf.movingout2.runtime.RuntimeBundleValidator
import com.dnctf.movingout2.runtime.RuntimeGraphicsComponents
import com.dnctf.movingout2.settings.LauncherSettings
import com.dnctf.movingout2.ui.home.LauncherHomeView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var graphicsStatus: TextView
    private lateinit var repository: InstallationRepository
    private val gamepadTranslator = GamepadTranslator()
    private val inputBridge = RuntimeInputBridge()
    private var touchControllerState = com.dnctf.movingout2.input.ControllerState()
    private var physicalControllerState = com.dnctf.movingout2.input.ControllerState()
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            gamepadTranslator.releaseAll()
            physicalControllerState = com.dnctf.movingout2.input.ControllerState()
            sendCombinedInput()
        }
    }
    private val pickFolder = 1001
    private val pickRuntimeBundle = 1002
    private val pickGameZip = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = InstallationRepository(this)
        inputBridge.initialize(RuntimeLaunchConfig(filesDir).sharedMemoryFile.absolutePath)
        val views = LauncherHomeView.create(this, ::openFolderPicker, ::revalidateSavedFolder, ::openRuntimeBundlePicker, ::openGameZipPicker, ::showSettings, ::showControllerTest)
        status = views.status
        runtimeStatus = views.runtimeStatus
        graphicsStatus = views.graphicsStatus
        setContentView(views.root)
        status.text = if (repository.savedUri() != null) "Saved installation folder found. Tap Revalidate to check current access." else "Import the game ZIP or select an existing game folder. Runtime: RUNTIME_NOT_INSTALLED."
        refreshRuntimeStatus()
        probeGraphicsAsync()
    }

    private fun showControllerTest() {
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 12) }
        val info = TextView(this).apply { text = "Touch the controls or connect a controller.\nMO2X: " + if (inputBridge.isNativeAvailable() && inputBridge.isInitialized()) "native shared memory ready; Wine reader pending" else if (inputBridge.isInitialized()) "JVM fallback active; native shared memory unavailable" else "transport initialization failed"; textSize = 14f }
        val stateText = TextView(this).apply { text = "Input: idle"; textSize = 13f; setPadding(0, 8, 0, 8) }
        val pad = VirtualControllerView(this) { state ->
            touchControllerState = state
            sendCombinedInput()
            val readback = inputBridge.readSnapshot(0)?.joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) } ?: "unavailable"
            stateText.text = "Input: buttons=" + state.buttons.joinToString() + " | left=(" + state.leftX.toInt() + ", " + state.leftY.toInt() + ")\\nMO2X packet: " + readback
        }
        container.addView(info)
        container.addView(stateText)
        container.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 460))
        dialog.setContentView(container)
        dialog.setTitle("Controller test")
        dialog.setOnDismissListener {
            touchControllerState = com.dnctf.movingout2.input.ControllerState()
            sendCombinedInput()
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.96f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0) {
            gamepadTranslator.onKey(event)?.let { physicalControllerState = it; sendCombinedInput(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0) {
            gamepadTranslator.onMotion(event)?.let { physicalControllerState = it; sendCombinedInput(); return true }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(INPUT_SERVICE) as? InputManager)?.registerInputDeviceListener(inputDeviceListener, null)
    }

    override fun onPause() {
        (getSystemService(INPUT_SERVICE) as? InputManager)?.unregisterInputDeviceListener(inputDeviceListener)
        touchControllerState = com.dnctf.movingout2.input.ControllerState()
        physicalControllerState = gamepadTranslator.releaseAll()
        inputBridge.disconnectPlayer(0)
        super.onPause()
    }

    override fun onDestroy() {
        inputBridge.destroy()
        super.onDestroy()
    }

    private fun sendCombinedInput() {
        inputBridge.sendCombined(0, touchControllerState, physicalControllerState, connected = true)
    }

    private fun openFolderPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, pickFolder)
    }

    private fun openRuntimeBundlePicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, pickRuntimeBundle)
    }

    private fun openGameZipPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, pickGameZip)
    }

    private fun revalidateSavedFolder() {
        val uri = repository.savedUri()
        status.text = if (uri == null) "No installation folder saved." else runCatching {
            formatReport(GameInstallationManager(this).validate(uri))
        }.getOrElse { "Folder access error: ${it.message ?: "permission unavailable"}" }
    }

    private fun showSettings() {
        val current = LauncherSettings.load(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 8) }
        fun slider(label: String, value: Int): SeekBar {
            val labelView = TextView(this).apply { text = "$label: $value%"; textSize = 16f }
            panel.addView(labelView)
            val bar = SeekBar(this).apply { max = 100; progress = value }
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
        AlertDialog.Builder(this).setTitle("Performance & audio")
            .setMessage("Baseline target: Snapdragon 855+, Low graphics, 70–75% resolution scale, 30 FPS. Saved values are preferences only; runtime application is not implemented yet.")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val selectedFps = listOf(30, 45, 60)[fps.selectedItemPosition.coerceIn(0, 2)]
                val selectedPreset = presetOptions[preset.selectedItemPosition.coerceIn(0, presetOptions.lastIndex)]
                LauncherSettings.save(this, LauncherSettings(resolution.progress.coerceIn(50, 100), selectedFps, selectedPreset, master.progress, music.progress, sfx.progress))
                Toast.makeText(this, "Settings saved (runtime application pending).", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun probeGraphicsAsync() {
        graphicsStatus.text = "Probing Vulkan loader and physical device..."
        Thread({
            val result = runCatching { VulkanCapabilities.probe(this) }
            runOnUiThread {
                graphicsStatus.text = result.fold({ probe ->
                    buildString {
                        append("Native Vulkan: ").append(probe.nativeProbeStatus ?: "unavailable")
                        append("\nAPI: ").append(probe.apiVersion ?: "unknown")
                        append("\nGPU: ").append(probe.gpuVendor ?: "unknown").append(" / ").append(probe.gpuName ?: "unknown")
                        append("\nExtensions: ").append(probe.extensions.size)
                        if (probe.blockers.isNotEmpty()) append("\n\nNotes:\n- ").append(probe.blockers.joinToString("\n- "))
                    }
                }, { "Graphics probe failed: ${it.message ?: it.javaClass.simpleName}" })
            }
        }, "graphics-probe").apply { isDaemon = true; start() }
    }

    private fun refreshRuntimeStatus() {
        val root = File(filesDir, "native_runtime/bundle")
        val report = RuntimeBundleValidator().validate(root)
        val graphics = RuntimeGraphicsComponents.assess(root)
        runtimeStatus.text = if (report.isReady) {
            "Runtime bundle: STRUCTURE_VALIDATED\nWine/Box64: payload present (execution not verified)\nDXVK: ${graphics.dxvk}\nTurnip: ${graphics.turnip}\n${graphics.notes.joinToString("\n")}\nGame launch remains blocked pending native process/graphics integration and device tests."
        } else {
            "Runtime bundle: ${report.status}\nDXVK: ${graphics.dxvk}\nTurnip: ${graphics.turnip}\n${report.blockers.joinToString("\n")}\n${graphics.notes.joinToString("\n")}"
        }
    }

    @Deprecated("Use Activity Result API in a later refactor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            pickFolder -> try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                repository.saveUri(uri)
                status.text = formatReport(GameInstallationManager(this).validate(uri))
            } catch (e: Exception) { status.text = "Folder import failed safely: ${e.message ?: e.javaClass.simpleName}" }
            pickRuntimeBundle -> try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val imported = RuntimeBundleImporter(this).importTree(uri)
                val graphics = RuntimeGraphicsComponents.assess(File(filesDir, "native_runtime/bundle"))
                runtimeStatus.text = "Runtime bundle: STRUCTURE_VALIDATED\nCopied ${imported.copiedFiles} files (${imported.copiedBytes} bytes).\nDXVK: ${graphics.dxvk}\nTurnip: ${graphics.turnip}\n${graphics.notes.joinToString("\n")}\nExecution/driver loading not verified; launch remains blocked."
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
                Thread({
                    val result = runCatching {
                        GameZipImporter(this).importZip(uri, cancelled = { cancelled.get() }, onProgress = { progress ->
                            runOnUiThread {
                                progressDialog.setProgress(progress.percent)
                                val mibDone = progress.bytesDone / (1024 * 1024)
                                val mibTotal = progress.totalBytes / (1024 * 1024)
                                progressDialog.setMessage("${progress.phase}: ${progress.percent}% (${progress.entriesDone}/${progress.totalEntries} entries, ${mibDone}/${mibTotal} MiB)")
                            }
                        })
                    }
                    runOnUiThread {
                        progressDialog.dismiss()
                        status.text = result.fold({ imported ->
                            buildString {
                                append("ZIP import successful. Files: ").append(imported.fileCount)
                                append("\nExpanded: ").append(imported.bytesWritten / (1024 * 1024)).append(" MiB")
                                append("\nImported game root: ").append(imported.gameRoot.absolutePath)
                                repository.registerImported(imported.gameRoot.absolutePath)
                                val report = com.dnctf.movingout2.installation.InstallationValidator().validate(imported.gameRoot)
                                append("\n\nGame validation: ").append(report.status)
                                report.stages.forEach { stage -> append("\n").append(stage.stage).append(": ").append(if (stage.passed) "PASS" else "FAIL") }
                                append("\nRuntime: RUNTIME_NOT_INSTALLED")
                            }
                        }, { "ZIP import failed safely: ${it.message ?: it.javaClass.simpleName}" })
                    }
                }, "game-zip-import").apply { isDaemon = true; start() }
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
            append("\n\nRuntime: RUNTIME_NOT_INSTALLED")
        }
}
