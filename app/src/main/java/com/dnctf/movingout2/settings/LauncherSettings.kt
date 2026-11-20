package com.dnctf.movingout2.settings

import android.content.Context

data class LauncherSettings(
    val resolutionScale: Int = 75,
    val fpsTarget: Int = 30,
    val graphicsPreset: String = "Balanced",
    val masterVolume: Int = 80,
    val musicVolume: Int = 75,
    val sfxVolume: Int = 85
) {
    companion object {
        private const val PREFS = "launcher_settings"
        fun load(context: Context): LauncherSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return LauncherSettings(
                p.getInt("resolutionScale", 75).coerceIn(50, 100),
                p.getInt("fpsTarget", 30).let { if (it in listOf(30, 45, 60)) it else 30 },
                p.getString("graphicsPreset", "Balanced") ?: "Balanced",
                p.getInt("masterVolume", 80).coerceIn(0, 100),
                p.getInt("musicVolume", 75).coerceIn(0, 100),
                p.getInt("sfxVolume", 85).coerceIn(0, 100)
            )
        }
        fun save(context: Context, settings: LauncherSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("resolutionScale", settings.resolutionScale)
                .putInt("fpsTarget", settings.fpsTarget)
                .putString("graphicsPreset", settings.graphicsPreset)
                .putInt("masterVolume", settings.masterVolume)
                .putInt("musicVolume", settings.musicVolume)
                .putInt("sfxVolume", settings.sfxVolume)
                .apply()
        }
    }
}
