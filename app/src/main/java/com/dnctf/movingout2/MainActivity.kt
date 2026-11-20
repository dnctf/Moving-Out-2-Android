package com.dnctf.movingout2

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val pickFolder = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 32)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "Moving Out 2 Android"
            textSize = 26f
        }
        val subtitle = TextView(this).apply {
            text = "Dedicated Windows-game compatibility research launcher"
            textSize = 15f
        }
        status = TextView(this).apply {
            text = "Status: Runtime not integrated"
            textSize = 16f
            setPadding(0, 28, 0, 24)
        }
        val select = Button(this).apply {
            text = "Select game folder"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, pickFolder)
            }
        }
        val disclaimer = TextView(this).apply {
            text = "This app currently validates a local game installation only. Game execution, Wine/Box64, graphics, audio and controller support are not yet implemented or verified. Provide your own legally obtained game files."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        content.addView(title)
        content.addView(subtitle)
        content.addView(status)
        content.addView(select)
        content.addView(disclaimer)
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    @Deprecated("Use Activity Result API in a later refactor")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFolder || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }
        status.text = validateFolder(uri)
    }

    private fun validateFolder(uri: Uri): String {
        val root = DocumentFile.fromTreeUri(this, uri)
            ?: return "Validation failed: folder access could not be opened."
        val requiredFiles = listOf("mo2.exe", "GameAssembly.dll", "UnityPlayer.dll", "baselib.dll")
        val requiredDirs = listOf("MovingOut2_Data")
        val missing = mutableListOf<String>()
        requiredFiles.forEach { name ->
            if (!root.findFile(name)?.isFile == true) missing += name
        }
        requiredDirs.forEach { name ->
            if (root.findFile(name)?.isDirectory != true) missing += "$name/"
        }
        val dataDir = root.findFile("MovingOut2_Data")
        val il2cpp = dataDir?.findFile("il2cpp_data")?.findFile("Metadata")
        if (il2cpp?.findFile("global-metadata.dat")?.isFile != true) {
            missing += "MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat"
        }
        return if (missing.isEmpty()) {
            "Installation validation: PASS (expected files detected)\n\nRuntime status: NOT IMPLEMENTED"
        } else {
            "Installation validation: INCOMPLETE\nMissing items:\n- " +
                missing.joinToString("\n- ") + "\n\nRuntime status: NOT IMPLEMENTED"
        }
    }
}
