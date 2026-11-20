package com.dnctf.movingout2.ui.installation

data class InstallationUiState(val selectedUri: String? = null, val validationSummary: String = "Not validated", val missingFiles: List<String> = emptyList(), val accessErrors: List<String> = emptyList())