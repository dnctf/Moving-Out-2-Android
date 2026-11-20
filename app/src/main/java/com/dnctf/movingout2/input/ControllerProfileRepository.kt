package com.dnctf.movingout2.input

import org.json.JSONObject

data class ControllerProfile(
    val schemaVersion: Int = 1,
    val name: String,
    val buttonMap: Map<String, String> = emptyMap(),
    val sensitivity: Float = 1f,
    val deadzone: Float = .12f
) {
    fun exportToJson(): String {
        validate()
        val mappings = JSONObject()
        buttonMap.toSortedMap().forEach { (key, value) -> mappings.put(key, value) }
        return JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("name", name)
            .put("sensitivity", sensitivity.toDouble())
            .put("deadzone", deadzone.toDouble())
            .put("buttonMap", mappings)
            .toString(2)
    }

    fun validate() {
        require(schemaVersion == 1) { "Unsupported controller profile schema." }
        require(name.isNotBlank()) { "Profile name cannot be blank." }
        require(sensitivity in .1f..3f) { "Sensitivity must be between 0.1 and 3.0." }
        require(deadzone in 0f..0.5f) { "Deadzone must be between 0 and 0.5." }
        require(buttonMap.keys.all { it.matches(Regex("[A-Z0-9_]{1,16}")) }) { "Invalid mapping source key." }
        require(buttonMap.values.all { it.matches(Regex("[A-Z0-9_]{1,16}")) }) { "Invalid mapping target." }
    }

    companion object {
        val presets = listOf(
            ControllerProfile(name = "Default Co-Op"),
            ControllerProfile(name = "Touch Trigger-Swap", buttonMap = mapOf("LT" to "RT", "RT" to "LT")),
            ControllerProfile(name = "Standard XInput Gamepad")
        )

        fun importFromJson(json: String): ControllerProfile {
            require(json.length < 65536) { "Controller profile is too large." }
            val root = JSONObject(json)
            val version = root.optInt("schemaVersion", -1)
            require(version == 1) { "Unsupported controller profile schema." }
            val mappings = root.optJSONObject("buttonMap") ?: JSONObject()
            val map = linkedMapOf<String, String>()
            mappings.keys().forEach { key -> map[key] = mappings.optString(key) }
            val profile = ControllerProfile(
                schemaVersion = version,
                name = root.optString("name"),
                buttonMap = map,
                sensitivity = root.optDouble("sensitivity", 1.0).toFloat(),
                deadzone = root.optDouble("deadzone", .12).toFloat()
            )
            profile.validate()
            return profile
        }
    }
}