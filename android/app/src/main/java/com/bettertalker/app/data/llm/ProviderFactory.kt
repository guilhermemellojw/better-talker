package com.bettertalker.app.data.llm

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

    /** F20-F1 — resolve provider+chave das Settings numa leitura só. */
    suspend fun resolveRemote(settings: SettingsStore): RemoteConfig {
        val selected = settings.llmProvider.first()
        return if (selected == PROVIDER_QWEN) {
            RemoteConfig(PROVIDER_QWEN, settings.groqApiKey.first().orEmpty())
        } else {
            RemoteConfig(PROVIDER_GEMINI, settings.llmApiKey.first().orEmpty())
        }
    }

    /** Instancia o provider da config resolvida (puro/testável). */
    fun createFor(config: RemoteConfig): LlmProvider =
        if (config.providerId == PROVIDER_QWEN) QwenProvider(apiKey = config.apiKey)
        else GeminiProvider(apiKey = config.apiKey)

    /** Rota nova ativa? Só com chave configurada. Puro/testável. */
    fun useRemoteRoute(apiKey: String?): Boolean = !apiKey.isNullOrBlank()

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
