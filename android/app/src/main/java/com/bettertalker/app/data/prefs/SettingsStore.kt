package com.bettertalker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bettertalker.app.data.security.KeystoreSecretCipher
import com.bettertalker.app.data.security.SecretCipher
import com.bettertalker.app.domain.help.HelpTriggerCodec
import com.bettertalker.app.domain.help.HelpTriggerId
import com.bettertalker.app.domain.help.HelpTriggerState
import com.bettertalker.app.domain.onboarding.OnboardingV5State
import com.bettertalker.app.ui.theme.PaletteId
import com.bettertalker.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

val Context.store by preferencesDataStore("settings")

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

    /** Voz pt-BR do motor do sistema (null = padrão do aparelho). */
    private val TTS_VOICE_NAME = stringPreferencesKey("tts_voice_name")
    val ttsVoiceName: Flow<String?> = ctx.store.data.map { it[TTS_VOICE_NAME] }
    suspend fun setTtsVoiceName(v: String?) {
        ctx.store.edit {
            if (v.isNullOrBlank()) it.remove(TTS_VOICE_NAME) else it[TTS_VOICE_NAME] = v
        }
    }

    /** STT: preferir o reconhecedor no aparelho (offline) quando existir. */
    private val STT_ON_DEVICE = booleanPreferencesKey("stt_on_device")
    val sttOnDevice: Flow<Boolean> = ctx.store.data.map { it[STT_ON_DEVICE] ?: true }
    suspend fun setSttOnDevice(v: Boolean) { ctx.store.edit { it[STT_ON_DEVICE] = v } }

    /**
     * M7: opt-in explícito do beta de sincronização. Desligado por padrão,
     * inclusive no flavor syncBeta; sem ele nenhum worker é agendado.
     */
    private val SYNC_BETA_ENABLED = booleanPreferencesKey("sync_beta_enabled")
    val syncBetaEnabled: Flow<Boolean> = ctx.store.data.map { it[SYNC_BETA_ENABLED] ?: false }
    suspend fun syncBetaEnabledValue(): Boolean = syncBetaEnabled.first()
    suspend fun setSyncBetaEnabled(v: Boolean) { ctx.store.edit { it[SYNC_BETA_ENABLED] = v } }

    /** Sync (M2): primeiro sync aguardando escolha (Enviar/Baixar/Mesclar). */
    private val SYNC_FIRST_PENDING = booleanPreferencesKey("sync_first_pending")
    val syncFirstPending: Flow<Boolean> = ctx.store.data.map { it[SYNC_FIRST_PENDING] ?: false }
    suspend fun syncFirstPendingValue(): Boolean = syncFirstPending.first()
    suspend fun setSyncFirstPending(v: Boolean) { ctx.store.edit { it[SYNC_FIRST_PENDING] = v } }

    /** STT: motor de reconhecimento ("system" | "sherpa"). */
    private val STT_ENGINE = stringPreferencesKey("stt_engine")
    val sttEngine: Flow<String> = ctx.store.data.map { it[STT_ENGINE] ?: "system" }
    suspend fun setSttEngine(v: String) {
        ctx.store.edit { it[STT_ENGINE] = if (v == "sherpa") "sherpa" else "system" }
    }

    /**
     * Treino MVP: última preparação escolhida, por nota (escopo|modo|timer).
     * A string é opaca aqui; o StageViewModel codifica/decodifica e valida
     * ids contra o banco (seção removida → discurso inteiro).
     */
    private fun trainPrepKey(noteId: String) =
        stringPreferencesKey("train_prep_$noteId")

    suspend fun trainPrep(noteId: String): String? =
        ctx.store.data.map { it[trainPrepKey(noteId)] }.first()

    /** Observa a preparação da nota (cartão da aba Treino). */
    fun trainPrepFlow(noteId: String): Flow<String?> =
        ctx.store.data.map { it[trainPrepKey(noteId)] }

    suspend fun setTrainPrep(noteId: String, value: String) {
        ctx.store.edit { it[trainPrepKey(noteId)] = value }
    }

    /**
     * Apresentar: última sessão do palco por nota (“índice|segundos|epoch”).
     * String opaca aqui; o ViewModel valida o índice contra as seções.
     */
    private fun presentSessionKey(noteId: String) =
        stringPreferencesKey("present_session_$noteId")

    suspend fun presentSession(noteId: String): String? =
        ctx.store.data.map { it[presentSessionKey(noteId)] }.first()

    suspend fun setPresentSession(noteId: String, value: String) {
        ctx.store.edit { it[presentSessionKey(noteId)] = value }
    }

    /** Aba Treino: esboço atual (independente do Palco). */
    private val TRAIN_NOTE_ID = stringPreferencesKey("train_note_id")
    val trainNoteId: Flow<String?> = ctx.store.data.map { it[TRAIN_NOTE_ID] }
    suspend fun setTrainNoteId(id: String?) {
        ctx.store.edit { if (id.isNullOrBlank()) it.remove(TRAIN_NOTE_ID) else it[TRAIN_NOTE_ID] = id }
    }

    /** Aba Palco: esboço atual (independente do Treino). */
    private val PRESENT_NOTE_ID = stringPreferencesKey("present_note_id")
    val presentNoteId: Flow<String?> = ctx.store.data.map { it[PRESENT_NOTE_ID] }
    suspend fun setPresentNoteId(id: String?) {
        ctx.store.edit { if (id.isNullOrBlank()) it.remove(PRESENT_NOTE_ID) else it[PRESENT_NOTE_ID] = id }
    }

    /** Navegação global: última nota aberta (Treinar/Apresentar direto da barra). */
    private val LAST_NOTE_ID = stringPreferencesKey("last_note_id")
    val lastNoteId: Flow<String?> = ctx.store.data.map { it[LAST_NOTE_ID] }
    suspend fun setLastNoteId(id: String) { ctx.store.edit { it[LAST_NOTE_ID] = id } }

    // ---------- v5.0: onboarding, ajuda e gatilhos ----------

    private val ONBOARDING_V5_STATE = stringPreferencesKey("onboarding_v5_state")
    val onboardingV5State: Flow<OnboardingV5State> = ctx.store.data.map {
        OnboardingV5State.fromName(it[ONBOARDING_V5_STATE])
    }

    suspend fun setOnboardingV5State(state: OnboardingV5State) {
        ctx.store.edit { it[ONBOARDING_V5_STATE] = state.name }
    }

    private val UPGRADE_V5_SEEN = booleanPreferencesKey("upgrade_v5_seen")
    private val UPGRADE_V5_DISMISSED = booleanPreferencesKey("upgrade_v5_dismissed")

    val upgradeV5Seen: Flow<Boolean> = ctx.store.data.map { it[UPGRADE_V5_SEEN] ?: false }
    val upgradeV5Dismissed: Flow<Boolean> =
        ctx.store.data.map { it[UPGRADE_V5_DISMISSED] ?: false }

    /** Só métrica/migração: nunca suprime o resgate do sheet. */
    suspend fun markUpgradeV5Seen() {
        ctx.store.edit { it[UPGRADE_V5_SEEN] = true }
    }

    /**
     * Fonte de verdade do “não mostrar mais”. Também consolida o estado de
     * onboarding para que a 5.0.1 não repita o sheet automaticamente.
     */
    suspend fun markUpgradeV5Dismissed() {
        ctx.store.edit {
            it[UPGRADE_V5_DISMISSED] = true
            val current = OnboardingV5State.fromName(it[ONBOARDING_V5_STATE])
            if (current == OnboardingV5State.NOT_STARTED ||
                current == OnboardingV5State.UPGRADE_SEEN
            ) {
                it[ONBOARDING_V5_STATE] = OnboardingV5State.UPGRADE_SEEN.name
            }
        }
        incrementHelpMetric("upgrade_welcome_dismissed")
    }

    private val HELP_TRIGGERS = stringPreferencesKey("help_triggers_v5")

    suspend fun helpTriggerStates(): Map<HelpTriggerId, HelpTriggerState> =
        HelpTriggerCodec.decode(ctx.store.data.first()[HELP_TRIGGERS])

    suspend fun markHelpTriggerSeen(id: HelpTriggerId, now: Long) {
        ctx.store.edit { prefs ->
            val current = HelpTriggerCodec.decode(prefs[HELP_TRIGGERS])
            val state = current[id] ?: HelpTriggerState()
            prefs[HELP_TRIGGERS] = HelpTriggerCodec.encode(
                current + (id to state.copy(seen = true, lastShownAt = now))
            )
        }
    }

    suspend fun dismissHelpTrigger(id: HelpTriggerId) {
        ctx.store.edit { prefs ->
            val current = HelpTriggerCodec.decode(prefs[HELP_TRIGGERS])
            val state = current[id] ?: HelpTriggerState()
            prefs[HELP_TRIGGERS] = HelpTriggerCodec.encode(
                current + (id to state.copy(dismissed = true))
            )
        }
    }

    /** Contadores locais da ajuda; nada é enviado. */
    suspend fun incrementHelpMetric(name: String) {
        val key = intPreferencesKey("help_metric_$name")
        ctx.store.edit { prefs -> prefs[key] = (prefs[key] ?: 0) + 1 }
    }

    val helpMetrics: Flow<Map<String, Int>> = ctx.store.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith("help_metric_") || value !is Int) {
                null
            } else {
                key.name.removePrefix("help_metric_") to value
            }
        }.toMap()
    }
    suspend fun clearLastNoteId() { ctx.store.edit { it.remove(LAST_NOTE_ID) } }
}

/** Ensaio (v1): limites do tamanho da fonte de leitura (sp). */
const val STAGE_FONT_MIN_SP = 16
const val STAGE_FONT_DEFAULT_SP = 24
const val STAGE_FONT_MAX_SP = 48
