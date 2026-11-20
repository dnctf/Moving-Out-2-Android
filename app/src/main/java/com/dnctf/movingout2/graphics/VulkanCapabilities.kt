package com.dnctf.movingout2.graphics

data class VulkanCapabilities(val available: Boolean, val apiVersion: String? = null, val extensions: Set<String> = emptySet(), val gpuVendor: String? = null, val gpuName: String? = null, val blockers: List<String> = emptyList())