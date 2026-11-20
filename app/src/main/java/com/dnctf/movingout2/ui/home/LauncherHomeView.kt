package com.dnctf.movingout2.ui.home

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class LauncherHomeViews(val root: ScrollView, val status: TextView)

object LauncherHomeView {
    fun create(context: Context, onSelectFolder: () -> Unit, onRevalidate: () -> Unit): LauncherHomeViews {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply { text = "Moving Out 2 Android"; textSize = 26f }
        val subtitle = TextView(context).apply { text = "Dedicated Windows-game compatibility launcher"; textSize = 15f }
        val status = TextView(context).apply { textSize = 16f; setPadding(0, 28, 0, 24) }
        val select = Button(context).apply { text = "Select game folder"; setOnClickListener { onSelectFolder() } }
        val revalidate = Button(context).apply { text = "Revalidate saved folder"; setOnClickListener { onRevalidate() } }
        val disclaimer = TextView(context).apply {
            text = "This app validates game files only. Wine/Box64 execution, graphics, audio and controller support are not implemented. Use legally obtained game files."
            textSize = 13f; setPadding(0, 24, 0, 0)
        }
        content.addView(title); content.addView(subtitle); content.addView(status); content.addView(select); content.addView(revalidate); content.addView(disclaimer)
        val scroll = ScrollView(context).apply { addView(content) }
        return LauncherHomeViews(scroll, status)
    }
}