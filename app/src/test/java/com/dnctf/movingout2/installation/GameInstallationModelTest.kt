package com.dnctf.movingout2.installation

import com.dnctf.movingout2.core.model.GameInstallation
import com.dnctf.movingout2.core.model.InstallationSource
import org.junit.Assert.assertEquals
import org.junit.Test

class GameInstallationModelTest {
    @Test fun modelsBothInstallationSources() {
        val saf = GameInstallation("saf:uri", InstallationSource.SAF_DIRECTORY, "content://folder")
        val imported = GameInstallation("internal:/data/game", InstallationSource.INTERNAL_IMPORTED, "/data/game")
        assertEquals(InstallationSource.SAF_DIRECTORY, saf.source)
        assertEquals(InstallationSource.INTERNAL_IMPORTED, imported.source)
    }
}
