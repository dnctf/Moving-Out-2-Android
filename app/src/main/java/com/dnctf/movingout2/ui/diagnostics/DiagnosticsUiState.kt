package com.dnctf.movingout2.ui.diagnostics

data class DiagnosticsUiState(val runtimeStatus: String = "RUNTIME_NOT_INSTALLED", val messages: List<String> = emptyList())