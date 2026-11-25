package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import com.dnctf.movingout2.core.model.GameInstallation
import com.dnctf.movingout2.core.model.InstallationSource
import java.io.File

data class RegisteredGame(
    val id: String,
    val displayName: String,
    val source: InstallationSource,
    val location: String,
    val validationStatus: InstallationStatus,
    val executableFile: File? = null,
    val missingFiles: List<String> = emptyList(),
    val accessErrors: List<String> = emptyList(),
    val unityVersion: String? = null,
    val metadataVersion: Int? = null,
    val architecture: String? = null,
    val lastValidatedEpochMs: Long? = null
) {
    val isReadyToLaunch: Boolean
        get() = validationStatus == InstallationStatus.VALID &&
                architecture == "x86_64" &&
                (executableFile?.isFile == true || source == InstallationSource.SAF_DIRECTORY)
}

class GameRegistry(
    private val context: Context,
    private val repository: InstallationRepository = InstallationRepository(context),
    private val safManager: GameInstallationManager = GameInstallationManager(context),
    private val fileValidator: InstallationValidator = InstallationValidator()
) {
    @Synchronized
    fun registerSafFolder(uri: Uri): RegisteredGame {
        repository.saveUri(uri)
        return validateAndCacheSaf(uri)
    }

    @Synchronized
    fun registerImportedZip(gameRoot: File, displayName: String = "Moving Out 2 (Imported)"): RegisteredGame {
        repository.registerImported(gameRoot.absolutePath, displayName)
        return validateAndCacheImported(gameRoot.absolutePath, displayName)
    }

    @Synchronized
    fun getActiveGame(): RegisteredGame? {
        val installations = repository.allInstallations()
        if (installations.isEmpty()) {
            val savedUri = repository.savedUri()
            if (savedUri != null) return validateAndCacheSaf(savedUri)
            val internalRoot = File(context.filesDir, "game_import")
            val candidate = findGameRoot(internalRoot)
            if (candidate != null) return registerImportedZip(candidate)
            return null
        }

        // Return the first valid or most recently validated installation
        for (item in installations) {
            val validated = revalidate(item.installationId)
            if (validated != null && validated.validationStatus == InstallationStatus.VALID) {
                return validated
            }
        }
        return installations.firstOrNull()?.let { revalidate(it.installationId) }
    }

    @Synchronized
    fun revalidate(id: String): RegisteredGame? {
        val item = repository.allInstallations().firstOrNull { it.installationId == id } ?: return null
        return when (item.source) {
            InstallationSource.SAF_DIRECTORY -> {
                val uri = runCatching { Uri.parse(item.location) }.getOrNull() ?: return null
                validateAndCacheSaf(uri)
            }
            InstallationSource.INTERNAL_IMPORTED -> {
                validateAndCacheImported(item.location, item.displayName)
            }
        }
    }

    @Synchronized
    fun revalidateAll(): List<RegisteredGame> {
        return repository.allInstallations().mapNotNull { revalidate(it.installationId) }
    }

    @Synchronized
    fun clear() {
        repository.clear()
    }

    private fun validateAndCacheSaf(uri: Uri): RegisteredGame {
        val report = safManager.validate(uri)
        val status = if (report.isComplete) InstallationStatus.VALID else if (report.missing.isNotEmpty()) InstallationStatus.INCOMPLETE else InstallationStatus.ACCESS_ERROR
        val now = System.currentTimeMillis()
        val id = "saf:$uri"
        repository.markValidated(id, now)

        return RegisteredGame(
            id = id,
            displayName = "Moving Out 2 (SAF)",
            source = InstallationSource.SAF_DIRECTORY,
            location = uri.toString(),
            validationStatus = status,
            executableFile = null,
            missingFiles = report.missing,
            accessErrors = report.accessErrors,
            unityVersion = report.detectedUnityVersion,
            metadataVersion = report.metadataVersion,
            architecture = report.architecture,
            lastValidatedEpochMs = now
        )
    }

    private fun validateAndCacheImported(rootPath: String, displayName: String): RegisteredGame {
        val rootFile = File(rootPath)
        val report = fileValidator.validate(rootFile)
        val now = System.currentTimeMillis()
        val id = "internal:$rootPath"
        repository.markValidated(id, now)

        val exe = File(rootFile, "mo2.exe").takeIf { it.isFile }
        val metadataDat = File(rootFile, "MovingOut2_Data/il2cpp_data/Metadata/global-metadata.dat")
        val metadataVersion = if (metadataDat.isFile) {
            runCatching { metadataDat.inputStream().use { UnityGameProfileDetector.readBounded(it, 8) } }
                .getOrNull()?.let(UnityGameProfileDetector::detectMetadataVersion)
        } else null

        val globalManagers = File(rootFile, "MovingOut2_Data/globalgamemanagers")
        val unityVersion = if (globalManagers.isFile) {
            runCatching { globalManagers.inputStream().use { UnityGameProfileDetector.readBounded(it, 256 * 1024) } }
                .getOrNull()?.let(UnityGameProfileDetector::detectUnityVersion)
        } else null

        val arch = exe?.let {
            runCatching { it.inputStream().use { s -> UnityGameProfileDetector.readBounded(s, 64 * 1024) } }
                .getOrNull()?.let(UnityGameProfileDetector::detectPeArchitecture)
        }

        return RegisteredGame(
            id = id,
            displayName = displayName,
            source = InstallationSource.INTERNAL_IMPORTED,
            location = rootPath,
            validationStatus = report.status,
            executableFile = exe,
            missingFiles = report.missingFiles,
            accessErrors = report.accessErrors,
            unityVersion = unityVersion,
            metadataVersion = metadataVersion,
            architecture = arch,
            lastValidatedEpochMs = now
        )
    }

    private fun findGameRoot(base: File): File? {
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(base to 0)
        while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeFirst()
            if (File(dir, "mo2.exe").isFile && File(dir, "MovingOut2_Data").isDirectory) return dir
            if (depth >= 12) continue
            dir.listFiles()?.filter { it.isDirectory }?.forEach { pending.add(it to depth + 1) }
        }
        return null
    }
}
