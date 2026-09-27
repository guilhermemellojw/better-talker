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

    /** Provider com a chave atual das Settings (pode estar vazia). */
    suspend fun create(settings: SettingsStore): LlmProvider =
        createWithKey(settings.llmApiKey.first().orEmpty())

    /** Provider com chave já resolvida (evita reler o Flow). */
    fun createWithKey(apiKey: String): LlmProvider = GeminiProvider(apiKey = apiKey)

    /** Rota nova ativa? Só com chave configurada. Puro/testável. */
    fun useRemoteRoute(apiKey: String?): Boolean = !apiKey.isNullOrBlank()
}
