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
    @Test fun sensitivityBoostPreservesRadialStickLimit() {
        val profile = ControllerProfile(name = "Radial boost", sensitivity = 3f, deadzone = 0f)
        val output = profile.apply(ControllerState(leftX = .6f, leftY = .6f))
        val magnitudeSquared = output.leftX * output.leftX + output.leftY * output.leftY
        assertTrue(magnitudeSquared <= 1.0001f)
        assertEquals(output.leftX, output.leftY, .0001f)
    }

    @Test fun perGameProfileKeysAreStableDistinctAndSafe() {
        val first = controllerProfileStorageKey("internal:/games/mo2")
        val same = controllerProfileStorageKey("internal:/games/mo2")
        val second = controllerProfileStorageKey("saf:content://tree/another")
        assertEquals(first, same)
        assertTrue(first.matches(Regex("game-[a-f0-9]{32}")))
        assertTrue(second.matches(Regex("game-[a-f0-9]{32}")))
        assertTrue(first != second)
        assertEquals("moving-out-2", controllerProfileStorageKey(null))
    }

    @Test fun alreadyNormalizedTouchInputCanSkipProfileDeadzone() {
        val profile = ControllerProfile(name = "Touch once", deadzone = .12f)
        val sample = VirtualControllerEngine.radialDeadzone(.13f, 0f, profile.deadzone)
        val output = profile.apply(ControllerState(leftX = sample.x), applyDeadzone = false)
        assertEquals(sample.x, output.leftX, .0001f)
    }
    @Test fun nonFiniteTriggersAreNeutralizedByProfileApplication() {
        val profile = ControllerProfile(name = "Safe triggers")
        val output = profile.apply(ControllerState(leftTrigger = Float.NaN, rightTrigger = Float.POSITIVE_INFINITY))
        assertEquals(0f, output.leftTrigger, 0f)
        assertEquals(0f, output.rightTrigger, 0f)
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
    @Test fun importRejectsCoercibleButWrongJsonTypesAndUnknownFields() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":"1","name":"bad"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":1,"name":42}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":1,"name":"bad","sensitivity":"1.2"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson("""{"schemaVersion":1,"name":"bad","unexpected":true}""")
        }
    }

    @Test fun importEnforcesUtf8ByteLimitRatherThanCharacterCount() {
        val multiByte = "😀".repeat(17000)
        assertTrue(multiByte.length < 65536)
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson(multiByte)
        }
    }

    @Test fun boundedReaderRejectsMalformedUtf8() {
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedUtf8Profile(byteArrayOf(0xC3.toByte()).inputStream())
        }
    }

    @Test fun oversizeImportIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ControllerProfile.importFromJson(" ".repeat(65537))
        }
    }
    @Test fun boundedStreamImportAcceptsExactLimitAndRejectsOneByteOver() {
        val exact = "x".repeat(1024)
        assertEquals(exact, readBoundedUtf8Profile(exact.byteInputStream(), 1024))
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedUtf8Profile(("x".repeat(1025)).byteInputStream(), 1024)
        }
    }
    @Test fun boundedStreamDoesNotConsumeUnboundedOversizeInput() {
        val oversized = "x".repeat(200000).byteInputStream()
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedUtf8Profile(oversized, 64)
        }
        assertTrue("Reader stops after detecting overflow", oversized.available() > 0)
    }
    @Test fun invalidByteLimitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { readBoundedUtf8Profile("".byteInputStream(), -1) }
        assertThrows(IllegalArgumentException::class.java) { readBoundedUtf8Profile("".byteInputStream(), 65537) }
    }
}
