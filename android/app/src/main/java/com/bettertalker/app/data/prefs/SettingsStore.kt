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

    /**
     * F20-F1 — chave BYOD do Groq/Qwen (tela Modelo IA). Mesmas garantias da
     * chave Gemini: DataStore local, nunca em log/erro, só no header
     * Authorization do POST HTTPS. DEV-ONLY: chave embutida em APK debug
     * pode ser extraída — nunca distribuir com segredo embutido.
     */
    private val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
    val groqApiKey: Flow<String> = ctx.store.data.map { it[GROQ_API_KEY].orEmpty() }
    suspend fun setGroqApiKey(v: String) { ctx.store.edit { it[GROQ_API_KEY] = v.trim() } }
    suspend fun clearGroqApiKey() { ctx.store.edit { it.remove(GROQ_API_KEY) } }

    /**
     * F20-F1 / T3 — provider de IA selecionado:
     * "auto" (default) | "deepseek" | "gemini" | "qwen" | "gemma_local".
     *
     * "auto" resolve em runtime (T3 DeepSeek): DeepSeek com chave e online →
     * outro remoto com chave e online (Gemini, Groq) → Gemma local (modelo
     * presente) → determinístico. Valores explícitos são preservados.
     */
    private val LLM_PROVIDER = stringPreferencesKey("llm_provider")
    val llmProvider: Flow<String> = ctx.store.data.map { it[LLM_PROVIDER] ?: "auto" }
    suspend fun setLlmProvider(v: String) {
        ctx.store.edit {
            it[LLM_PROVIDER] = when (v) {
                "deepseek" -> "deepseek"
                "gemini" -> "gemini"
                // F2: provider on-device (Gemma 4 E2B via LiteRT-LM)
                "gemma_local" -> "gemma_local"
                // T3: decisão automática remoto → local → determinístico
                "auto" -> "auto"
                else -> "qwen"
            }
        }
    }

    /**
     * T3 (DeepSeek) — chave BYOD do DeepSeek. Mesmas garantias das demais:
     * DataStore local, nunca em log/erro, só no header Authorization do POST.
     */
    private val DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
    val deepseekApiKey: Flow<String> = ctx.store.data.map { it[DEEPSEEK_API_KEY].orEmpty() }
    suspend fun setDeepseekApiKey(v: String) { ctx.store.edit { it[DEEPSEEK_API_KEY] = v.trim() } }
    suspend fun clearDeepseekApiKey() { ctx.store.edit { it.remove(DEEPSEEK_API_KEY) } }

    /** T3/T4 — modelo DeepSeek escolhido ("deepseek-flash" | "deepseek-v4-pro"). */
    private val DEEPSEEK_MODEL = stringPreferencesKey("deepseek_model")
    val deepseekModel: Flow<String> = ctx.store.data.map { it[DEEPSEEK_MODEL] ?: "deepseek-flash" }
    suspend fun setDeepseekModel(v: String) {
        ctx.store.edit {
            it[DEEPSEEK_MODEL] = if (v == "deepseek-v4-pro") "deepseek-v4-pro" else "deepseek-flash"
        }
    }
}
