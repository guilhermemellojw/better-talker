package com.bettertalker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bettertalker.app.data.security.KeystoreSecretCipher
import com.bettertalker.app.data.security.SecretCipher
import com.bettertalker.app.ui.theme.PaletteId
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

class SettingsStore(
    private val ctx: Context,
    private val secretCipher: SecretCipher = KeystoreSecretCipher(),
) {
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
     * Conta vinculada a este aparelho. Guard mínimo de multi-conta: enquanto
     * não houver ownerUid completo, trocar de conta exige apagar os dados
     * locais para não vazar notas para outra conta.
     */
    private val LAST_UID = stringPreferencesKey("last_uid")
    val lastUid: Flow<String?> = ctx.store.data.map { it[LAST_UID] }
    suspend fun lastUidValue(): String? = lastUid.first()
    suspend fun setLastUid(v: String?) {
        ctx.store.edit {
            if (v == null) it.remove(LAST_UID) else it[LAST_UID] = v
        }
    }

    /**
     * T3 (DeepSeek) — chave BYOD do DeepSeek. Única chave de IA do app
     * (cleanup multi-LLM). O valor é cifrado com AES/GCM e a chave AES fica
     * no Android Keystore; o DataStore guarda só o envelope. Nunca em log,
     * erro, telemetria ou backup (allowBackup=false). Só sai do aparelho no
     * POST HTTPS.
     */
    private val DEEPSEEK_API_KEY_LEGACY = stringPreferencesKey("deepseek_api_key")
    private val DEEPSEEK_API_KEY_ENCRYPTED =
        stringPreferencesKey("deepseek_api_key_encrypted")

    val deepseekApiKey: Flow<String> = ctx.store.data.map { prefs ->
        val encrypted = prefs[DEEPSEEK_API_KEY_ENCRYPTED]
        if (encrypted != null) {
            secretCipher.decrypt(encrypted).orEmpty()
        } else {
            // Compatibilidade durante a migração: some no primeiro
            // migrateLegacySecrets() bem-sucedido.
            prefs[DEEPSEEK_API_KEY_LEGACY].orEmpty()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun setDeepseekApiKey(v: String) {
        val trimmed = v.trim()
        if (trimmed.isBlank()) {
            clearDeepseekApiKey()
            return
        }
        // Se o Keystore falhar, a exceção sobe: nunca gravamos texto claro.
        val encrypted = secretCipher.encrypt(trimmed)
        ctx.store.edit {
            it[DEEPSEEK_API_KEY_ENCRYPTED] = encrypted
            it.remove(DEEPSEEK_API_KEY_LEGACY)
        }
    }

    suspend fun clearDeepseekApiKey() {
        ctx.store.edit {
            it.remove(DEEPSEEK_API_KEY_ENCRYPTED)
            it.remove(DEEPSEEK_API_KEY_LEGACY)
        }
    }

    /**
     * A1 — migra a chave histórica em texto claro para o envelope cifrado e
     * limpa ciphertext que não decifra mais (Keystore invalidado/restaurado).
     * Idempotente; chamado cedo no app, em IO.
     */
    suspend fun migrateLegacySecrets() {
        val prefs = ctx.store.data.first()
        val encrypted = prefs[DEEPSEEK_API_KEY_ENCRYPTED]
        val legacy = prefs[DEEPSEEK_API_KEY_LEGACY].orEmpty().trim()
        when {
            encrypted != null -> {
                val valid = secretCipher.decrypt(encrypted) != null
                val hasLegacy = prefs[DEEPSEEK_API_KEY_LEGACY] != null
                if (!valid || hasLegacy) {
                    ctx.store.edit {
                        if (!valid) {
                            it.remove(DEEPSEEK_API_KEY_ENCRYPTED)
                            it.remove(DEEPSEEK_API_KEY_LEGACY)
                        } else {
                            it.remove(DEEPSEEK_API_KEY_LEGACY)
                        }
                    }
                }
            }
            legacy.isNotBlank() -> {
                val encoded = secretCipher.encrypt(legacy)
                ctx.store.edit {
                    it[DEEPSEEK_API_KEY_ENCRYPTED] = encoded
                    it.remove(DEEPSEEK_API_KEY_LEGACY)
                }
            }
        }
    }

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

    /** Fase 2 (mentor): a dica do ✨ no ponto ativo já foi mostrada uma vez. */
    private val MENTOR_HINT_SEEN = booleanPreferencesKey("mentor_hint_seen")
    val mentorHintSeen: Flow<Boolean> = ctx.store.data.map { it[MENTOR_HINT_SEEN] ?: false }
    suspend fun setMentorHintSeen() { ctx.store.edit { it[MENTOR_HINT_SEEN] = true } }

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

    /**
     * Treino MVP: última preparação escolhida, por nota (escopo|modo|timer).
     * A string é opaca aqui; o StageViewModel codifica/decodifica e valida
     * ids contra o banco (seção removida → discurso inteiro).
     */
    private fun trainPrepKey(noteId: String) =
        stringPreferencesKey("train_prep_$noteId")

    suspend fun trainPrep(noteId: String): String? =
        ctx.store.data.map { it[trainPrepKey(noteId)] }.first()

    suspend fun setTrainPrep(noteId: String, value: String) {
        ctx.store.edit { it[trainPrepKey(noteId)] = value }
    }

    /** Navegação global: última nota aberta (Treinar/Apresentar direto da barra). */
    private val LAST_NOTE_ID = stringPreferencesKey("last_note_id")
    val lastNoteId: Flow<String?> = ctx.store.data.map { it[LAST_NOTE_ID] }
    suspend fun setLastNoteId(id: String) { ctx.store.edit { it[LAST_NOTE_ID] = id } }
    suspend fun clearLastNoteId() { ctx.store.edit { it.remove(LAST_NOTE_ID) } }
}

/** Ensaio (v1): limites do tamanho da fonte de leitura (sp). */
const val STAGE_FONT_MIN_SP = 16
const val STAGE_FONT_DEFAULT_SP = 24
const val STAGE_FONT_MAX_SP = 48
