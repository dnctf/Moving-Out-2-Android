package com.dnctf.movingout2.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RuntimeBundleEntryNameRegistryTest {
    @Test fun acceptsDistinctSafeSiblingNames() {
        val registry = RuntimeBundleEntryNameRegistry()
        assertEquals("bin", registry.register("bin"))
        assertEquals("lib", registry.register("lib"))
        assertEquals("runtime-manifest.json", registry.register("runtime-manifest.json"))
    }

    @Test fun rejectsCaseInsensitiveCollision() {
        val registry = RuntimeBundleEntryNameRegistry()
        registry.register("Wine")
        assertThrows(IOException::class.java) { registry.register("wine") }
    }

    @Test fun rejectsUnicodeCompatibilityCollision() {
        val registry = RuntimeBundleEntryNameRegistry()
        registry.register("Ａ")
        assertThrows(IOException::class.java) { registry.register("A") }
    }

    @Test fun rejectsTraversalAndSeparators() {
        val registry = RuntimeBundleEntryNameRegistry()
        listOf("../escape", "a/b", "a\\b", ".", "..", "").forEach { name ->
            assertThrows(IOException::class.java) { registry.register(name) }
        }
    }
}
