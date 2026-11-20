package com.dnctf.movingout2.ui.home

data class HomeUiState(val installationLabel: String, val runtimeLabel: String = "RUNTIME_NOT_INSTALLED", val canLaunch: Boolean = false)