package com.bettertalker.app.data.llm

import android.content.Context
import com.bettertalker.app.data.llm.litert.LitertGemmaEngine
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Fase 18 — BLOCO A. Decisão única de provider (§7).
 *
 * Fluxo: Settings → ProviderFactory → GeminiProvider.
 * A UI nunca instancia provider direto. Sem fallback silencioso:
 * sem chave, o GeminiProvider responde em modo offline explícito
 * (marcado `offline=true`); a ViewModel decide a rota por `hasApiKey`.
 */
object ProviderFactory {

    /** Ids de provider remoto. Default "gemini" (comportamento preservado). */
    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_QWEN = "qwen"

    /** F2 — provider on-device (Gemma 4 E2B / LiteRT-LM). Sem chave. */
    const val PROVIDER_LOCAL_GEMMA = "gemma_local"

    /** T3 — decisão automática: local (modelo presente) → remoto com chave → determinístico. */
    const val PROVIDER_AUTO = "auto"

    /** Provider com a chave atual das Settings (pode estar vazia). */
    suspend fun create(settings: SettingsStore): LlmProvider =
        createWithKey(settings.llmApiKey.first().orEmpty())

    /** Provider com chave já resolvida (evita reler o Flow). */
    fun createWithKey(apiKey: String): LlmProvider = GeminiProvider(apiKey = apiKey)

    /**
     * F20-F1 — provider selecionado nas Settings (tela Modelo IA).
     * "qwen" => Groq/Qwen com a chave Groq; qualquer outro => Gemini.
     * Sem fallback silencioso: a chave ausente vira erro honesto no provider.
     */
    suspend fun createSelected(settings: SettingsStore): LlmProvider {
        val selected = settings.llmProvider.first()
        return if (selected == PROVIDER_QWEN) {
            QwenProvider(apiKey = settings.groqApiKey.first().orEmpty())
        } else {
            create(settings)
        }
    }

    /** Config remota resolvida: qual provider + qual chave (para a ViewModel). */
    data class RemoteConfig(val providerId: String, val apiKey: String)

    /**
     * F20-F1/T3 — resolve provider+chave das Settings numa leitura só.
     * Com "auto", consulta a presença do modelo quando o [context] é dado
     * (UI/roteamento); sem contexto, "auto" resolve só pela ordem de chaves.
     */
    suspend fun resolveRemote(settings: SettingsStore, context: Context? = null): RemoteConfig {
        val selected = settings.llmProvider.first()
        val geminiKey = settings.llmApiKey.first().orEmpty()
        val groqKey = settings.groqApiKey.first().orEmpty()
        val modelPresent = selected == PROVIDER_AUTO && context != null &&
            LitertGemmaEngine.isModelPresent(context)
        return resolveProvider(selected, modelPresent, geminiKey, groqKey)
    }

    /**
     * T3 — resolução pura/testável. Ordem do "auto": Gemma local (modelo
     * presente) → Gemini (chave) → Qwen (chave Groq) → determinístico
     * (Gemini sem chave ⇒ `useRemoteRoute` falso). Providers explícitos
     * mantêm o comportamento anterior.
     */
    fun resolveProvider(
        selected: String,
        modelPresent: Boolean,
        geminiKey: String,
        groqKey: String,
    ): RemoteConfig = when {
        selected == PROVIDER_QWEN -> RemoteConfig(PROVIDER_QWEN, groqKey)
        // local não tem chave; a rota vale mesmo com chave vazia.
        selected == PROVIDER_LOCAL_GEMMA -> RemoteConfig(PROVIDER_LOCAL_GEMMA, "")
        selected == PROVIDER_AUTO && modelPresent -> RemoteConfig(PROVIDER_LOCAL_GEMMA, "")
        selected == PROVIDER_AUTO && geminiKey.isNotBlank() -> RemoteConfig(PROVIDER_GEMINI, geminiKey)
        selected == PROVIDER_AUTO && groqKey.isNotBlank() -> RemoteConfig(PROVIDER_QWEN, groqKey)
        else -> RemoteConfig(PROVIDER_GEMINI, geminiKey)
    }

    /** Instancia o provider da config resolvida (puro/testável). */
    fun createFor(config: RemoteConfig): LlmProvider =
        if (config.providerId == PROVIDER_QWEN) QwenProvider(apiKey = config.apiKey)
        else GeminiProvider(apiKey = config.apiKey)

    /**
     * F2 — variante com contexto para o provider on-device. Remotos seguem
     * idênticos; só "gemma_local" precisa de Context (engine LiteRT).
     */
    fun createFor(config: RemoteConfig, context: android.content.Context): LlmProvider =
        if (config.providerId == PROVIDER_LOCAL_GEMMA) {
            LocalGemmaProvider(context)
        } else {
            createFor(config)
        }

    /** Rota nova ativa? Só com chave configurada. Puro/testável. */
    fun useRemoteRoute(apiKey: String?): Boolean = !apiKey.isNullOrBlank()

    /**
     * F2 — rota ativa considerando o provider resolvido: o local não exige
     * chave; remotos continuam exigindo.
     */
    fun useRemoteRoute(config: RemoteConfig): Boolean =
        config.providerId == PROVIDER_LOCAL_GEMMA || useRemoteRoute(config.apiKey)

    /**
     * Cria um FallbackLlmProvider lendo as duas chaves (Groq e Gemini).
     *
     * Ordem: Qwen (Groq) → Gemini.
     * - Só Groq configurada: fallback com 1 provider (QwenProvider)
     * - Só Gemini configurada: fallback com 1 provider (GeminiProvider)
     * - Nenhuma: GeminiProvider offline (não quebra contrato)
     */
    suspend fun createFallback(settings: SettingsStore): LlmProvider {
        val groqKey = settings.groqApiKey.first().orEmpty()
        val geminiKey = settings.llmApiKey.first().orEmpty()
        val providers = buildList {
            if (groqKey.isNotBlank()) add(QwenProvider(apiKey = groqKey))
            if (geminiKey.isNotBlank()) add(GeminiProvider(apiKey = geminiKey))
        }
        return if (providers.isEmpty()) {
            GeminiProvider(apiKey = "")
        } else {
            FallbackLlmProvider(providers)
        }
    }
}
