package com.dnctf.movingout2.compatibility

class CompatibilityChecker {
    fun assess(architecture: String?, vulkanAvailable: Boolean, blockers: List<String> = emptyList()): List<String> =
        buildList {
            if (architecture != "x86_64") add("Game executable architecture is not confirmed x86_64.")
            if (!vulkanAvailable) add("Vulkan is unavailable on this device.")
            addAll(blockers)
        }
}