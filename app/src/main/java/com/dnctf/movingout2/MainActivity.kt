package com.dnctf.movingout2

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dnctf.movingout2.installation.GameInstallationManager
import com.dnctf.movingout2.installation.InstallationRepository

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var repository: InstallationRepository
    private val pickFolder = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = InstallationRepository(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 32)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "Moving Out 2 Android"; textSize = 26f }
        val subtitle = TextView(this).apply { text = "Dedicated Windows-game compatibility launcher"; textSize = 15f }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 28, 0, 24) }
        val select = Button(this).apply {
            text = "Select game folder"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, pickFolder)
            }
        }
        val validateSaved = Button(this).apply {
            text = "Revalidate saved folder"
            setOnClickListener { repository.savedUri()?.let { status.text = formatReport(GameInstallationManager(this@MainActivity).validate(it)) } ?: run { status.text = "No installation folder saved." } }
        }
        val disclaimer = TextView(this).apply {
            text = "This app validates game files only. Wine/Box64 execution, graphics, audio and controller support are not implemented. Use legally obtained game files."
            textSize = 13f; setPadding(0, 24, 0, 0)
        }
        content.addView(title); content.addView(subtitle); content.addView(status); content.addView(select); content.addView(validateSaved); content.addView(disclaimer)
        scroll.addView(content); root.addView(scroll); setContentView(root)
        repository.savedUri()?.let { status.text = "Saved installation folder found. Tap Revalidate to check current access." } ?: run { status.text = "Installation folder not configured. Runtime: RUNTIME_NOT_INSTALLED." }
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