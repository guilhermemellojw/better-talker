package com.bettertalker.app.data.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 3 — Persistência da URI da pasta Downloads (SAF tree).
 *
 * Usa o mesmo DataStore de settings. A URI é persistida como string.
 */
object DownloadsTreeStore {
    private val KEY = stringPreferencesKey("downloads_tree_uri")

    /** Retorna a URI persistida (ou null). */
    fun getUri(ctx: Context): Flow<Uri?> =
        ctx.store.data.map { prefs ->
            prefs[KEY]?.let { Uri.parse(it) }
        }

    /** Persiste a URI. */
    suspend fun setUri(ctx: Context, uri: Uri) {
        ctx.store.edit { it[KEY] = uri.toString() }
    }

    /** Remove a URI persistida. */
    suspend fun clearUri(ctx: Context) {
        ctx.store.edit { it.remove(KEY) }
    }
}
