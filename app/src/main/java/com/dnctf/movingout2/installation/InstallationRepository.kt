package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri
import com.dnctf.movingout2.core.model.GameInstallation
import com.dnctf.movingout2.core.model.InstallationSource
import org.json.JSONObject

class InstallationRepository(context: Context) {
    private val prefs = context.getSharedPreferences("installation", Context.MODE_PRIVATE)

    fun saveUri(uri: Uri) {
        prefs.edit().putString("game_tree_uri", uri.toString()).apply()
        register(GameInstallation("saf:${uri}", InstallationSource.SAF_DIRECTORY, uri.toString()))
    }
    fun savedUri(): Uri? = prefs.getString("game_tree_uri", null)?.let(Uri::parse)

    fun registerImported(rootPath: String, displayName: String = "Moving Out 2 (Imported)") {
        register(GameInstallation("internal:${rootPath}", InstallationSource.INTERNAL_IMPORTED, rootPath, displayName))
    }

    fun allInstallations(): List<GameInstallation> {
        val ids = prefs.getStringSet("installation_registry_ids", emptySet()).orEmpty()
        return ids.mapNotNull { id ->
            prefs.getString("installation_registry_${id}", null)?.let { raw ->
                runCatching {
                    val json = JSONObject(raw)
                    GameInstallation(
                        installationId = json.getString("id"),
                        source = InstallationSource.valueOf(json.getString("source")),
                        location = json.getString("location"),
                        displayName = json.optString("name", "Moving Out 2"),
                        lastValidatedEpochMs = json.optLong("validatedAt").takeIf { it > 0 }
                    )
                }.getOrNull()
            }
        }.sortedByDescending { it.lastValidatedEpochMs ?: 0L }
    }

    fun markValidated(id: String, epochMillis: Long = System.currentTimeMillis()) {
        val item = allInstallations().firstOrNull { it.installationId == id } ?: return
        register(item.copy(lastValidatedEpochMs = epochMillis))
    }

    fun clear() {
        val editor = prefs.edit().remove("game_tree_uri")
        prefs.getStringSet("installation_registry_ids", emptySet()).orEmpty().forEach { editor.remove("installation_registry_${it}") }
        editor.remove("installation_registry_ids").apply()
    }

    private fun register(item: GameInstallation) {
        val ids = prefs.getStringSet("installation_registry_ids", emptySet()).orEmpty().toMutableSet()
        ids += item.installationId
        val json = JSONObject().put("id", item.installationId).put("source", item.source.name)
            .put("location", item.location).put("name", item.displayName)
            .put("validatedAt", item.lastValidatedEpochMs ?: 0L)
        prefs.edit().putString("installation_registry_${item.installationId}", json.toString())
            .putStringSet("installation_registry_ids", ids).apply()
    }
}
