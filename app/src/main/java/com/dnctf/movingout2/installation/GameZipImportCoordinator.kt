package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException

data class GameZipImportSummary(
    val imported: GameZipImportResult,
    val validation: InstallationReport
)

/** Owns background ZIP import, registration and final validation; callbacks are delivered on main. */
class GameZipImportCoordinator(
    context: Context,
    private val repository: InstallationRepository
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun importAsync(
        uri: Uri,
        cancelled: () -> Boolean = { false },
        onProgress: (ImportProgress) -> Unit = {},
        onComplete: (Result<GameZipImportSummary>) -> Unit
    ) {
        Thread({
            val result = runCatching {
                val imported = GameZipImporter(appContext).importZip(uri, cancelled, onProgress = { progress ->
                    mainHandler.post { onProgress(progress) }
                })
                repository.registerImported(imported.gameRoot.absolutePath)
                val validation = InstallationValidator().validate(imported.gameRoot)
                if (validation.status != InstallationStatus.VALID || validation.missingFiles.isNotEmpty() ||
                    validation.accessErrors.isNotEmpty()) {
                    throw IOException("Imported game root failed final validation: ${validation.status}")
                }
                GameZipImportSummary(imported, validation)
            }
            mainHandler.post { onComplete(result) }
        }, "game-zip-import").apply { isDaemon = true; start() }
    }
}
