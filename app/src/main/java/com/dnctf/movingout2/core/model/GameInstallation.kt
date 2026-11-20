package com.dnctf.movingout2.core.model

enum class InstallationSource { SAF_DIRECTORY, INTERNAL_IMPORTED }

data class GameInstallation(
    val installationId: String,
    val source: InstallationSource,
    val location: String,
    val displayName: String = "Moving Out 2",
    val lastValidatedEpochMs: Long? = null
)
