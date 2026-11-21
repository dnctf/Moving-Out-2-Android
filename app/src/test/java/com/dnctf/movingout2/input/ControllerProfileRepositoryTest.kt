package com.dnctf.movingout2.input

import org.junit.Assert.*
import org.junit.Test

class ControllerProfileRepositoryTest {
    @Test fun jsonExportImportRoundTripPreservesMappingsAndSettings() {
        val profile = ControllerProfile(
            name = "Test profile",
            buttonMap = mapOf("A" to "B", "LT" to "RT"),
            axisMap = mapOf("LEFT_X" to "RIGHT_X", "RIGHT_TRIGGER" to "LEFT_TRIGGER"),
            sensitivity = 1.25f,
            deadzone = .2f
        )
        val restored = ControllerProfile.importFromJson(profile.exportToJson())
        assertEquals(profile.name, restored.name)
        assertEquals(profile.buttonMap, restored.buttonMap)
        assertEquals(profile.axisMap, restored.axisMap)
        assertEquals(profile.sensitivity, restored.sensitivity, 0f)
        assertEquals(profile.deadzone, restored.deadzone, 0f)
    }
    @Test fun schemaAndUnknownButtonOrAxisAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":2,"name":"future"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile(name="bad", buttonMap=mapOf("NOT_A_BUTTON" to "A")).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile(name="bad axis", axisMap=mapOf("LEFT_X" to "MAGIC")).validate()
        }
    }
    @Test fun presetCatalogIncludesRequestedModes() {
        assertEquals(3, ControllerProfile.presets.size)
        assertEquals("Moving Out 2 Co-Op Default", ControllerProfile.presets[0].name)
        assertEquals("Single-Handed / Trigger Swap", ControllerProfile.presets[1].name)
        assertEquals("Physical Gamepad Standard", ControllerProfile.presets[2].name)
        ControllerProfile.presets.forEach { it.validate() }
    }
    @Test fun applyingProfileRemapsButtonsAxesAndSensitivitySafely() {
        val p = ControllerProfile(name="Remap", buttonMap=mapOf("A" to "B"),
            axisMap=mapOf("LEFT_X" to "RIGHT_X"), sensitivity=2f, deadzone=0f)
        val out = p.apply(ControllerState(buttons=setOf(ControllerButton.A), leftX=.25f, rightX=.75f, leftTrigger=.5f))
        assertTrue(ControllerButton.B in out.buttons)
        assertEquals(1f, out.leftX, 0f)
        assertEquals(1f, out.leftTrigger, 0f)
    }
    @Test fun malformedMapTypesAndCrossGroupAxesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":1,"name":"bad","buttonMap":"A"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":1,"name":"bad","axisMap":{"LEFT_X":"LEFT_TRIGGER"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":1,"name":"bad","buttonMap":{"A":2} }""")
        }
    }
    @Test fun oversizeImportIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson(" ".repeat(65537))
        }
    }
}
