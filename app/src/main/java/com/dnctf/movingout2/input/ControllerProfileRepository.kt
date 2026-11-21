package com.dnctf.movingout2.input

import android.content.Context
import org.json.JSONObject

data class ControllerProfile(
    val schemaVersion: Int = 1,
    val name: String,
    val buttonMap: Map<String, String> = emptyMap(),
    val axisMap: Map<String, String> = emptyMap(),
    val sensitivity: Float = 1f,
    val deadzone: Float = .12f
) {
    fun exportToJson(): String {
        validate()
        val buttons = JSONObject().also { obj -> buttonMap.toSortedMap().forEach { (k, v) -> obj.put(k, v) } }
        val axes = JSONObject().also { obj -> axisMap.toSortedMap().forEach { (k, v) -> obj.put(k, v) } }
        return JSONObject().put("schemaVersion", schemaVersion).put("name", name)
            .put("sensitivity", sensitivity.toDouble()).put("deadzone", deadzone.toDouble())
            .put("buttonMap", buttons).put("axisMap", axes).toString(2)
    }

    fun validate() {
        require(schemaVersion == CURRENT_SCHEMA) { "Unsupported controller profile schema." }
        require(name.isNotBlank() && name.length <= 80) { "Profile name must contain 1–80 characters." }
        require(sensitivity.isFinite() && sensitivity in .1f..3f) { "Sensitivity must be between 0.1 and 3.0." }
        require(deadzone.isFinite() && deadzone in 0f..0.5f) { "Deadzone must be between 0 and 0.5." }
        require(buttonMap.keys.all { it in BUTTON_NAMES } && buttonMap.values.all { it in BUTTON_NAMES }) { "Unknown button mapping." }
        require(axisMap.keys.all { it in AXIS_NAMES } && axisMap.values.all { it in AXIS_NAMES }) { "Unknown axis mapping." }
    }

    fun remapButton(button: ControllerButton): ControllerButton =
        ControllerButton.valueOf(buttonMap[button.name] ?: button.name)

    fun apply(state: ControllerState): ControllerState {
        validate()
        val buttons = state.buttons.map(::remapButton).toSet()
        fun axis(target: String, fallback: Float): Float {
            val source = axisMap[target] ?: target
            val raw = when (source) {
                "LEFT_X" -> state.leftX; "LEFT_Y" -> state.leftY
                "RIGHT_X" -> state.rightX; "RIGHT_Y" -> state.rightY
                "LEFT_TRIGGER" -> state.leftTrigger; "RIGHT_TRIGGER" -> state.rightTrigger
                else -> fallback
            }
            return raw
        }
        val l = PhysicalGamepadNormalizer.normalizeStick(axis("LEFT_X", state.leftX), axis("LEFT_Y", state.leftY), deadzone)
        val r = PhysicalGamepadNormalizer.normalizeStick(axis("RIGHT_X", state.rightX), axis("RIGHT_Y", state.rightY), deadzone)
        val lt = (axis("LEFT_TRIGGER", state.leftTrigger) * sensitivity).coerceIn(0f, 1f)
        val rt = (axis("RIGHT_TRIGGER", state.rightTrigger) * sensitivity).coerceIn(0f, 1f)
        return ControllerState(buttons, l.x * sensitivity, l.y * sensitivity, r.x * sensitivity, r.y * sensitivity, lt, rt)
    }

    companion object {
        private const val CURRENT_SCHEMA = 1
        private const val PREFS = "controller_profiles_v1"
        private val BUTTON_NAMES = ControllerButton.values().map { it.name }.toSet()
        private val AXIS_NAMES = setOf("LEFT_X", "LEFT_Y", "RIGHT_X", "RIGHT_Y", "LEFT_TRIGGER", "RIGHT_TRIGGER")

        val presets = listOf(
            ControllerProfile(name = "Moving Out 2 Co-Op Default"),
            ControllerProfile(name = "Single-Handed / Trigger Swap", buttonMap = mapOf("LT" to "RT", "RT" to "LT"), axisMap = mapOf("LEFT_TRIGGER" to "RIGHT_TRIGGER", "RIGHT_TRIGGER" to "LEFT_TRIGGER")),
            ControllerProfile(name = "Physical Gamepad Standard")
        )

        fun importFromJson(json: String): ControllerProfile {
            require(json.length <= 65536) { "Controller profile is too large." }
            val root = JSONObject(json)
            require(root.optInt("schemaVersion", -1) == CURRENT_SCHEMA) { "Unsupported controller profile schema." }
            val buttons = root.optJSONObject("buttonMap") ?: JSONObject()
            val axes = root.optJSONObject("axisMap") ?: JSONObject()
            val profile = ControllerProfile(
                schemaVersion = root.optInt("schemaVersion"),
                name = root.optString("name"),
                buttonMap = buttons.keys().asSequence().associateWith { buttons.optString(it) },
                axisMap = axes.keys().asSequence().associateWith { axes.optString(it) },
                sensitivity = root.optDouble("sensitivity", 1.0).toFloat(),
                deadzone = root.optDouble("deadzone", .12).toFloat()
            )
            profile.validate()
            return profile
        }

        fun save(context: Context, gameKey: String, profile: ControllerProfile) {
            validateGameKey(gameKey); profile.validate()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(gameKey, profile.exportToJson()).apply()
        }
        fun load(context: Context, gameKey: String): ControllerProfile? {
            validateGameKey(gameKey)
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(gameKey, null)
                ?.let { runCatching { importFromJson(it) }.getOrNull() }
        }
        fun delete(context: Context, gameKey: String) {
            validateGameKey(gameKey)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(gameKey).apply()
        }
        private fun validateGameKey(key: String) {
            require(key.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) { "Invalid game profile key." }
        }
    }
}
