package com.bettertalker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bettertalker.app.ui.theme.PaletteId
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {
    /** Home: modo de visualização (GRID | LIST_COMPACT | LIST_DETAILED). */
    private val HOME_VIEW_MODE = stringPreferencesKey("home_view_mode")
    val homeViewMode: Flow<String> = ctx.store.data.map { it[HOME_VIEW_MODE] ?: "GRID" }
    suspend fun setHomeViewMode(v: String) { ctx.store.edit { it[HOME_VIEW_MODE] = v } }

    /** Textura de fundo global (NONE | PAPER | DOTS | GRID | NOISE). */
    private val HOME_TEXTURE = stringPreferencesKey("home_texture")
    val homeTexture: Flow<String> = ctx.store.data.map { it[HOME_TEXTURE] ?: "NONE" }
    suspend fun setHomeTexture(v: String) { ctx.store.edit { it[HOME_TEXTURE] = v } }

    /** Intensidade da textura de fundo (0..1). Default 5% (sutil). */
    private val TEXTURE_INTENSITY = floatPreferencesKey("texture_intensity")
    val textureIntensity: Flow<Float> = ctx.store.data.map { it[TEXTURE_INTENSITY] ?: 0.05f }
    suspend fun setTextureIntensity(v: Float) {
        ctx.store.edit { it[TEXTURE_INTENSITY] = v.coerceIn(0f, 1f) }
    }

    private val THEME = stringPreferencesKey("theme_mode")
    val themeMode: Flow<ThemeMode> = ctx.store.data.map {
        runCatching { ThemeMode.valueOf(it[THEME] ?: "AUTO") }.getOrDefault(ThemeMode.AUTO)
    }
    suspend fun setThemeMode(v: ThemeMode) { ctx.store.edit { it[THEME] = v.name } }

    /** #7d3: paleta curada escolhida (default SAMSUNG preserva o legado). */
    private val PALETTE = stringPreferencesKey("palette_id")
    val paletteId: Flow<PaletteId> = ctx.store.data.map {
        runCatching { PaletteId.valueOf(it[PALETTE] ?: "SAMSUNG") }
            .getOrDefault(PaletteId.SAMSUNG)
    }
    suspend fun setPaletteId(v: PaletteId) { ctx.store.edit { it[PALETTE] = v.name } }

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
     * T3 (DeepSeek) — chave BYOD do DeepSeek. Única chave de IA do app
     * (cleanup multi-LLM). DataStore local, nunca em log/erro/telemetria —
     * só sai do aparelho no POST HTTPS.
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

    /** T2 (chat minimalista): o usuário já usou o chat ao menos uma vez. */
    private val CHAT_SETUP_SEEN = booleanPreferencesKey("chat_setup_seen")
    val chatSetupSeen: Flow<Boolean> = ctx.store.data.map { it[CHAT_SETUP_SEEN] ?: false }
    suspend fun setChatSetupSeen() { ctx.store.edit { it[CHAT_SETUP_SEEN] = true } }

    /** Ensaio (v1) — tamanho da fonte de leitura (sp), persistente. */
    private val STAGE_FONT_SP =
        androidx.datastore.preferences.core.intPreferencesKey("stage_font_sp")
    val stageFontSp: Flow<Int> = ctx.store.data.map {
        it[STAGE_FONT_SP] ?: STAGE_FONT_DEFAULT_SP
    }
    suspend fun setStageFontSp(v: Int) {
        ctx.store.edit { it[STAGE_FONT_SP] = v.coerceIn(STAGE_FONT_MIN_SP, STAGE_FONT_MAX_SP) }
    }

    /** #23-fix-a: voz premium (Piper) ligada. Default desligado. */
    private val VOICE_PREMIUM = booleanPreferencesKey("voice_premium_enabled")
    /**
     * Voz premium (Piper) — default LIGADA: o modelo vem nos assets e é
     * provisionado no 1º Ensaio; sem UI de gerenciamento. Um "false" salvo
     * (legado do toggle removido) ainda é respeitado.
     */
    val voicePremiumEnabled: Flow<Boolean> = ctx.store.data.map { it[VOICE_PREMIUM] ?: true }
    suspend fun setVoicePremiumEnabled(v: Boolean) { ctx.store.edit { it[VOICE_PREMIUM] = v } }

    /** Navegação global: última nota aberta (Treinar/Apresentar direto da barra). */
    private val LAST_NOTE_ID = stringPreferencesKey("last_note_id")
    val lastNoteId: Flow<String?> = ctx.store.data.map { it[LAST_NOTE_ID] }
    suspend fun setLastNoteId(id: String) { ctx.store.edit { it[LAST_NOTE_ID] = id } }
}

/** Ensaio (v1): limites do tamanho da fonte de leitura (sp). */
const val STAGE_FONT_MIN_SP = 16
const val STAGE_FONT_DEFAULT_SP = 24
const val STAGE_FONT_MAX_SP = 48
