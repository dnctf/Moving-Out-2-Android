package com.dnctf.movingout2.settings

import android.content.Context

data class LauncherSettings(
    val resolutionScale: Int = 75,
    val fpsTarget: Int = 30,
    val graphicsPreset: String = "Balanced",
    val masterVolume: Int = 80,
    val musicVolume: Int = 75,
    val sfxVolume: Int = 85,
    val controllerScale: Int = 100,
    val controllerOpacity: Int = 44,
    val controllerOffsetX: Int = 50,
    val controllerOffsetY: Int = 50,
    val controllerEnabled: Boolean = true,
    val controllerHaptics: Boolean = false
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
                p.getInt("sfxVolume", 85).coerceIn(0, 100),
                p.getInt("controllerScale", 100).coerceIn(65, 150),
                p.getInt("controllerOpacity", 44).coerceIn(15, 100),
                p.getInt("controllerOffsetX", 50).coerceIn(35, 65),
                p.getInt("controllerOffsetY", 50).coerceIn(35, 65),
                p.getBoolean("controllerEnabled", true),
                p.getBoolean("controllerHaptics", false)
            )
        }
        fun save(context: Context, settings: LauncherSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("resolutionScale", settings.resolutionScale.coerceIn(50, 100))
                .putInt("fpsTarget", settings.fpsTarget.let { if (it in listOf(30, 45, 60)) it else 30 })
                .putString("graphicsPreset", settings.graphicsPreset)
                .putInt("masterVolume", settings.masterVolume.coerceIn(0, 100))
                .putInt("musicVolume", settings.musicVolume.coerceIn(0, 100))
                .putInt("sfxVolume", settings.sfxVolume.coerceIn(0, 100))
                .putInt("controllerScale", settings.controllerScale.coerceIn(65, 150))
                .putInt("controllerOpacity", settings.controllerOpacity.coerceIn(15, 100))
                .putInt("controllerOffsetX", settings.controllerOffsetX.coerceIn(35, 65))
                .putInt("controllerOffsetY", settings.controllerOffsetY.coerceIn(35, 65))
                .putBoolean("controllerEnabled", settings.controllerEnabled)
                .putBoolean("controllerHaptics", settings.controllerHaptics)
                .apply()
        }
    }
}
