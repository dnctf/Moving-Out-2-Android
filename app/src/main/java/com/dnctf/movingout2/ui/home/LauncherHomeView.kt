package com.dnctf.movingout2.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class LauncherHomeViews(val root: ScrollView, val status: TextView, val runtimeStatus: TextView, val graphicsStatus: TextView)

object LauncherHomeView {
    private const val INK = 0xFF172033.toInt()
    private const val MUTED = 0xFF667085.toInt()
    private const val ACCENT = 0xFFE58B32.toInt()
    private const val SURFACE = 0xFFFFFFFF.toInt()
    private const val CANVAS = 0xFFF4F6FA.toInt()
    private const val BORDER = 0xFFE5E9F0.toInt()

    fun create(
        context: Context,
        onSelectFolder: () -> Unit,
        onRevalidate: () -> Unit,
        onImportRuntime: () -> Unit,
        onImportRuntimeFolder: () -> Unit,
        onDownloadRuntime: () -> Unit,
        onImportGameZip: () -> Unit,
        onOpenSettings: () -> Unit,
        onControllerTest: () -> Unit,
        onExportDiagnostics: () -> Unit
    ): LauncherHomeViews {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun text(value: String, size: Float, color: Int = INK, bold: Boolean = false) =
            TextView(context).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
        fun card(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(SURFACE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), BORDER)
            }
            elevation = dp(2).toFloat()
        }
        fun addGap(parent: LinearLayout, height: Int = 12) {
            parent.addView(android.view.View(context), LinearLayout.LayoutParams(1, dp(height)))
        }
        fun action(label: String, primary: Boolean = false, click: () -> Unit) =
            Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                minHeight = dp(50)
                setTextColor(if (primary) Color.WHITE else INK)
                background = GradientDrawable().apply {
                    setColor(if (primary) ACCENT else 0xFFF0F2F6.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener { click() }
            }
        fun addAction(parent: LinearLayout, button: Button) {
            parent.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { bottomMargin = dp(9) })
        }
        fun sectionHeading(title: String, detail: String): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 18f, INK, true))
                addView(text(detail, 13f, MUTED))
            }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(22), dp(4), dp(28))
        }
        val hero = card()
        hero.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF172B4D.toInt(), 0xFF263F63.toInt())
        ).apply { cornerRadius = dp(22).toFloat() }
        hero.addView(text("MOVING OUT 2", 12f, 0xFFFFD7A8.toInt(), true))
        val title = text("Moving Out 2 Android", 27f, Color.WHITE, true)
        title.setPadding(0, dp(5), 0, dp(4))
        hero.addView(title)
        hero.addView(text("Windows game compatibility launcher", 14f, 0xFFD9E2F0.toInt()))
        hero.addView(text("ARM64 • Box64 / Wine • Vulkan", 12f, 0xFFB9C7D9.toInt()).apply { setPadding(0, dp(14), 0, 0) })
        content.addView(hero)
        addGap(content, 14)

        val gameCard = card()
        gameCard.addView(sectionHeading("1. Game files", "Import a ZIP archive or select an existing game folder."))
        addGap(gameCard, 12)
        val status = text("Checking game installation…", 14f, MUTED)
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.background = GradientDrawable().apply {
            setColor(0xFFF5F7FA.toInt()); cornerRadius = dp(12).toFloat()
        }
        gameCard.addView(status)
        addGap(gameCard, 12)
        addAction(gameCard, action("Import game from ZIP", true, onImportGameZip))
        addAction(gameCard, action("Select game folder", false, onSelectFolder))
        addAction(gameCard, action("Revalidate saved folder", false, onRevalidate))
        content.addView(gameCard)
        addGap(content, 12)

        val runtimeCard = card()
        runtimeCard.addView(sectionHeading("2. Runtime", "The app automatically downloads and validates the runtime on first launch. Use repair if setup failed."))
        addGap(runtimeCard)
        val runtimeStatus = text("Runtime status is being checked…", 13f, MUTED)
        runtimeCard.addView(runtimeStatus)
        addGap(runtimeCard)
        addAction(runtimeCard, action("Repair / download runtime", true, onDownloadRuntime))
        addAction(runtimeCard, action("Import runtime ZIP and extract", true, onImportRuntime)\n        addAction(runtimeCard, action("Import runtime folder (SAF)", false, onImportRuntimeFolder)))
        runtimeCard.addView(text("The runtime is distributed separately from the APK. Downloading it does not include or provide game files.", 12f, MUTED))
        content.addView(runtimeCard)
        addGap(content, 12)

        val deviceCard = card()
        deviceCard.addView(sectionHeading("3. Device readiness", "Check GPU / Vulkan support before attempting to launch."))
        addGap(deviceCard)
        val graphicsStatus = text("Vulkan diagnostic pending…", 13f, MUTED)
        deviceCard.addView(graphicsStatus)
        addGap(deviceCard)
        addAction(deviceCard, action("Graphics, performance & audio settings", false, onOpenSettings))
        addAction(deviceCard, action("Test touch controller / gamepad", false, onControllerTest))
        addAction(deviceCard, action("Export diagnostic report", false, onExportDiagnostics))
        content.addView(deviceCard)
        addGap(content, 16)
        content.addView(text(
            "Important: automated build success does not mean the game is playable yet. Runtime execution, Vulkan presentation and game compatibility still require device testing. You must provide your own legally obtained game files.",
            12f, MUTED
        ))

        val maxWidth = minOf(dp(760), context.resources.displayMetrics.widthPixels - dp(32))
        val centered = FrameLayout(context).apply {
            addView(content, FrameLayout.LayoutParams(maxWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        val scroll = ScrollView(context).apply {
            setBackgroundColor(CANVAS)
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
            addView(centered, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            contentDescription = "Moving Out 2 Android launcher"
        }
        return LauncherHomeViews(scroll, status, runtimeStatus, graphicsStatus)
    }
}
