package com.dnctf.movingout2

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.dnctf.movingout2.installation.GameInstallationManager
import com.dnctf.movingout2.installation.InstallationRepository
import com.dnctf.movingout2.ui.home.LauncherHomeView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var repository: InstallationRepository
    private val pickFolder = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = InstallationRepository(this)
        val views = LauncherHomeView.create(this, ::openFolderPicker, ::revalidateSavedFolder)
        status = views.status
        setContentView(views.root)
        status.text = if (repository.savedUri() != null) {
            "Saved installation folder found. Tap Revalidate to check current access."
        } else {
            "Installation folder not configured. Runtime: RUNTIME_NOT_INSTALLED."
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickFolder)
    }

    private fun revalidateSavedFolder() {
        val uri = repository.savedUri()
        status.text = if (uri == null) "No installation folder saved." else runCatching {
            formatReport(GameInstallationManager(this).validate(uri))
        }.getOrElse { "Folder access error: ${it.message ?: "permission unavailable"}" }
    }

    @Deprecated("Use Activity Result API in a later refactor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFolder || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            repository.saveUri(uri)
            status.text = formatReport(GameInstallationManager(this).validate(uri))
        } catch (e: SecurityException) {
            status.text = "Folder permission error: ${e.message ?: "SAF read permission denied"}"
        } catch (e: Exception) {
            status.text = "Installation check failed safely: ${e.message ?: e.javaClass.simpleName}"
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