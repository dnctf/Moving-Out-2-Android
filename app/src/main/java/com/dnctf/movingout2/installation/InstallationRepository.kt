package com.dnctf.movingout2.installation

import android.content.Context
import android.net.Uri

class InstallationRepository(context: Context) {
    private val prefs = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
    fun saveUri(uri: Uri) { prefs.edit().putString("game_tree_uri", uri.toString()).apply() }
    fun savedUri(): Uri? = prefs.getString("game_tree_uri", null)?.let(Uri::parse)
    fun clear() { prefs.edit().remove("game_tree_uri").apply() }
}