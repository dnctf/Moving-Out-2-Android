package com.dnctf.movingout2.ui.settings

data class SettingsUiState(val graphicsBackend: String = "UNCONFIGURED", val resolutionScale: Float = 1f, val controllerEnabled: Boolean = true)