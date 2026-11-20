package com.dnctf.movingout2.ui.home

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class LauncherHomeViews(val root: ScrollView, val status: TextView, val runtimeStatus: TextView)

object LauncherHomeView {
    fun create(
        context: Context,
        onSelectFolder: () -> Unit,
        onRevalidate: () -> Unit,
        onImportRuntime: () -> Unit
    ): LauncherHomeViews {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val title = TextView(context).apply { text = "Moving Out 2 Android"; textSize = 26f }
        val subtitle = TextView(context).apply { text = "Dedicated Windows-game compatibility launcher"; textSize = 15f }
        val status = TextView(context).apply { textSize = 16f; setPadding(0, 28, 0, 24) }
        val select = Button(context).apply { text = "Select game folder"; setOnClickListener { onSelectFolder() } }
        val revalidate = Button(context).apply { text = "Revalidate saved folder"; setOnClickListener { onRevalidate() } }
        val runtimeTitle = TextView(context).apply { text = "Native Runtime Bundle"; textSize = 20f; setPadding(0, 32, 0, 12) }
        val runtimeStatus = TextView(context).apply { textSize = 14f; setPadding(0, 8, 0, 12) }
        val importRuntime = Button(context).apply { text = "Import runtime bundle (SAF)"; setOnClickListener { onImportRuntime() } }
        val runtimeInfo = TextView(context).apply {
            text = "Bundle validation only. No Wine/Box64 binaries are supplied by this project; launch remains disabled until native integration is implemented."
            textSize = 13f; setPadding(0, 16, 0, 0)
        }
        val disclaimer = TextView(context).apply {
            text = "Game files must be user-provided. This app does not distribute Moving Out 2 files."
            textSize = 13f; setPadding(0, 24, 0, 0)
        }
        content.addView(title); content.addView(subtitle); content.addView(status)
        content.addView(select); content.addView(revalidate)
        content.addView(runtimeTitle); content.addView(runtimeStatus); content.addView(importRuntime); content.addView(runtimeInfo); content.addView(disclaimer)
        val scroll = ScrollView(context).apply { addView(content) }
        return LauncherHomeViews(scroll, status, runtimeStatus)
    }
}
