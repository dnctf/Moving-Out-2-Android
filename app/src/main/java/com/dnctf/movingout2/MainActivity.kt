package com.dnctf.movingout2

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.TextView
import com.dnctf.movingout2.installation.GameInstallationManager
import com.dnctf.movingout2.installation.InstallationRepository
import com.dnctf.movingout2.runtime.RuntimeBundleImporter
import com.dnctf.movingout2.graphics.VulkanCapabilities
import com.dnctf.movingout2.runtime.RuntimeBundleValidator
import com.dnctf.movingout2.ui.home.LauncherHomeView
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var graphicsStatus: TextView
    private lateinit var repository: InstallationRepository
    private val pickFolder = 1001
    private val pickRuntimeBundle = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = InstallationRepository(this)
        val views = LauncherHomeView.create(this, ::openFolderPicker, ::revalidateSavedFolder, ::openRuntimeBundlePicker)
        status = views.status
        runtimeStatus = views.runtimeStatus
        graphicsStatus = views.graphicsStatus
        setContentView(views.root)
        status.text = if (repository.savedUri() != null) {
            "Saved installation folder found. Tap Revalidate to check current access."
        } else {
            "Installation folder not configured. Runtime: RUNTIME_NOT_INSTALLED."
        }
        refreshRuntimeStatus()
        probeGraphicsAsync()
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickFolder)
    }

    private fun openRuntimeBundlePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickRuntimeBundle)
    }

    private fun revalidateSavedFolder() {
        val uri = repository.savedUri()
        status.text = if (uri == null) "No installation folder saved." else runCatching {
            formatReport(GameInstallationManager(this).validate(uri))
        }.getOrElse { "Folder access error: ${it.message ?: "permission unavailable"}" }
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
        runtimeStatus.text = if (report.isReady) {
            "Runtime bundle: STRUCTURE_VALIDATED\nWine/Box64 launch integration is not implemented; game launch remains blocked."
        } else {
            "Runtime bundle: ${report.status}\n${report.blockers.joinToString("\n")}"
        }
    }

    @Deprecated("Use Activity Result API in a later refactor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == pickFolder) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                repository.saveUri(uri)
                status.text = formatReport(GameInstallationManager(this).validate(uri))
            } catch (e: SecurityException) {
                status.text = "Folder permission error: ${e.message ?: "SAF read permission denied"}"
            } catch (e: Exception) {
                status.text = "Installation check failed safely: ${e.message ?: e.javaClass.simpleName}"
            }
        } else if (requestCode == pickRuntimeBundle) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val imported = RuntimeBundleImporter(this).importTree(uri)
                runtimeStatus.text = "Runtime bundle: STRUCTURE_VALIDATED\nCopied ${imported.copiedFiles} files (${imported.copiedBytes} bytes).\nWine/Box64 launch integration is not implemented; game launch remains blocked."
            } catch (e: Exception) {
                runtimeStatus.text = "Runtime bundle import failed: ${e.message ?: e.javaClass.simpleName}\nExisting runtime bundle is preserved when possible."
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
