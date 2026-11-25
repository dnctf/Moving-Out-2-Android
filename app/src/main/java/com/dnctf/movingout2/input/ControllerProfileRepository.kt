package com.dnctf.movingout2.input

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONObject

/** Maps arbitrary registry IDs to a stable key accepted by SharedPreferences validation. */
internal fun controllerProfileStorageKey(gameId: String?): String {
    val normalized = gameId?.trim()?.takeIf { it.isNotEmpty() } ?: return "moving-out-2"
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return "game-" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

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
        require(axisMap.all { (target, source) ->
            (target in STICK_AXES && source in STICK_AXES) || (target in TRIGGER_AXES && source in TRIGGER_AXES)
        }) { "Stick axes cannot be remapped to triggers or vice versa." }
    }

    fun remapButton(button: ControllerButton): ControllerButton =
        ControllerButton.valueOf(buttonMap[button.name] ?: button.name)

    fun apply(state: ControllerState, applyDeadzone: Boolean = true): ControllerState {
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
        val zone = if (applyDeadzone) deadzone else 0f
        val l = PhysicalGamepadNormalizer.normalizeStick(axis("LEFT_X", state.leftX), axis("LEFT_Y", state.leftY), zone)
        val r = PhysicalGamepadNormalizer.normalizeStick(axis("RIGHT_X", state.rightX), axis("RIGHT_Y", state.rightY), zone)
        fun scaledTrigger(raw: Float): Float {
            val safe = raw.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            return (safe * sensitivity).coerceIn(0f, 1f)
        }
        val lt = scaledTrigger(axis("LEFT_TRIGGER", state.leftTrigger))
        val rt = scaledTrigger(axis("RIGHT_TRIGGER", state.rightTrigger))
        // Sensitivity boost must preserve the circular stick boundary; clamping each
        // component independently would allow diagonal magnitude to exceed 1.0.
        val scaledLeft = PhysicalGamepadNormalizer.normalizeStick(l.x * sensitivity, l.y * sensitivity, 0f)
        val scaledRight = PhysicalGamepadNormalizer.normalizeStick(r.x * sensitivity, r.y * sensitivity, 0f)
        return ControllerState(buttons, scaledLeft.x, scaledLeft.y, scaledRight.x, scaledRight.y, lt, rt)
    }

    companion object {
        private const val CURRENT_SCHEMA = 1
        private const val PREFS = "controller_profiles_v1"
        private val BUTTON_NAMES = ControllerButton.values().map { it.name }.toSet()
        private val STICK_AXES = setOf("LEFT_X", "LEFT_Y", "RIGHT_X", "RIGHT_Y")
        private val TRIGGER_AXES = setOf("LEFT_TRIGGER", "RIGHT_TRIGGER")
        private val AXIS_NAMES = STICK_AXES + TRIGGER_AXES

        val presets = listOf(
            ControllerProfile(name = "Moving Out 2 Co-Op Default"),
            ControllerProfile(name = "Single-Handed / Trigger Swap", buttonMap = mapOf("LT" to "RT", "RT" to "LT"), axisMap = mapOf("LEFT_TRIGGER" to "RIGHT_TRIGGER", "RIGHT_TRIGGER" to "LEFT_TRIGGER")),
            ControllerProfile(name = "Physical Gamepad Standard")
        )

        fun importFromJson(json: String): ControllerProfile {
            require(json.toByteArray(Charsets.UTF_8).size <= 65536) { "Controller profile is too large." }
            try {
                val root = JSONObject(json)
                val allowedFields = setOf("schemaVersion", "name", "sensitivity", "deadzone", "buttonMap", "axisMap")
                require(root.keys().asSequence().all { it in allowedFields }) { "Unknown controller profile field." }

                val version = root.get("schemaVersion")
                require(version is Number && version.toDouble() == CURRENT_SCHEMA.toDouble()) {
                    "Unsupported controller profile schema."
                }
                val nameValue = root.get("name")
                require(nameValue is String) { "Profile name must be a string." }

                fun readNumber(field: String, default: Float): Float {
                    if (!root.has(field)) return default
                    val value = root.get(field)
                    require(value is Number && value.toDouble().isFinite()) { "$field must be a finite number." }
                    return value.toFloat()
                }
                fun readStringMap(field: String): Map<String, String> {
                    if (!root.has(field) || root.isNull(field)) return emptyMap()
                    val obj = root.optJSONObject(field) ?: throw IllegalArgumentException("$field must be a JSON object.")
                    return obj.keys().asSequence().associateWith { key ->
                        val value = obj.get(key)
                        require(value is String) { "$field values must be strings." }
                        value
                    }
                }
                val profile = ControllerProfile(
                    schemaVersion = version.toInt(),
                    name = nameValue,
                    buttonMap = readStringMap("buttonMap"),
                    axisMap = readStringMap("axisMap"),
                    sensitivity = readNumber("sensitivity", 1f),
                    deadzone = readNumber("deadzone", .12f)
                )
                profile.validate()
                return profile
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid controller profile JSON.", error)
            }
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

/** Thin persistence/import facade used by launcher UI and per-game registry. */
object ControllerProfileRepository {
    fun importFromJson(json: String): ControllerProfile = ControllerProfile.importFromJson(json)
    fun save(context: android.content.Context, gameKey: String, profile: ControllerProfile) = ControllerProfile.save(context, gameKey, profile)
    fun load(context: android.content.Context, gameKey: String): ControllerProfile? = ControllerProfile.load(context, gameKey)
    fun delete(context: android.content.Context, gameKey: String) = ControllerProfile.delete(context, gameKey)
}

/** Reads UTF-8 profile input with a hard byte limit before allocating unbounded memory. */
internal fun readBoundedUtf8Profile(input: InputStream, maxBytes: Int = 65536): String {
    require(maxBytes in 0..65536) { "Profile byte limit must be between 0 and 64 KiB." }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val readLimit = minOf(buffer.size, maxBytes - total + 1)
        val count = input.read(buffer, 0, readLimit)
        if (count < 0) break
        if (count == 0) {
            val next = input.read()
            if (next < 0) break
            if (total >= maxBytes) throw IllegalArgumentException("Controller profile exceeds 64 KiB.")
            output.write(next)
            total++
            continue
        }
        if (count > maxBytes - total) throw IllegalArgumentException("Controller profile exceeds 64 KiB.")
        output.write(buffer, 0, count)
        total += count
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Controller profile is not valid UTF-8.", error)
    }
}
