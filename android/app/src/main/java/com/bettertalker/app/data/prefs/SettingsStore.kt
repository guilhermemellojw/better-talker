package com.bettertalker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {
    private val SORT = stringPreferencesKey("sort")
    val sort: Flow<String> = ctx.store.data.map { it[SORT] ?: "updated" }
    suspend fun setSort(v: String) { ctx.store.edit { it[SORT] = v } }

    private val THEME = stringPreferencesKey("theme_mode")
    val themeMode: Flow<ThemeMode> = ctx.store.data.map {
        runCatching { ThemeMode.valueOf(it[THEME] ?: "AUTO") }.getOrDefault(ThemeMode.AUTO)
    }
    suspend fun setThemeMode(v: ThemeMode) { ctx.store.edit { it[THEME] = v.name } }

    private val REINDEX_V3 = stringPreferencesKey("reindexed_v3")
    private val INDEX_FORMAT = androidx.datastore.preferences.core.intPreferencesKey("index_format")
    suspend fun needsReindexV3(): Boolean = needsIndexFormat(3)

    /** Formato atual do índice. Bump => reindexa tudo uma vez. */
    suspend fun needsIndexFormat(current: Int): Boolean {
        var need = false
        ctx.store.edit {
            need = (it[INDEX_FORMAT] ?: 0) < current
            // mantém a flag legada marcada para não reindexar duas vezes
            it[REINDEX_V3] = "done"
            if (need) it[INDEX_FORMAT] = current
        }
        return need
    }

    private val LAST_SYNC = androidx.datastore.preferences.core.longPreferencesKey("last_sync")
    val lastSync: Flow<Long> = ctx.store.data.map { it[LAST_SYNC] ?: 0L }
    suspend fun setLastSync(v: Long) { ctx.store.edit { it[LAST_SYNC] = v } }

    /**
     * Fase 18 — chave BYOD do Gemini (tela Modelo IA). Mesmo DataStore das
     * demais settings: sem armazenamento paralelo. Opcional, local,
     * nunca em log/erro/telemetry — só sai do aparelho no POST HTTPS.
     */
    private val LLM_API_KEY = stringPreferencesKey("llm_api_key")
    val llmApiKey: Flow<String> = ctx.store.data.map { it[LLM_API_KEY].orEmpty() }
    suspend fun setLlmApiKey(v: String) { ctx.store.edit { it[LLM_API_KEY] = v.trim() } }
    suspend fun clearLlmApiKey() { ctx.store.edit { it.remove(LLM_API_KEY) } }
}
