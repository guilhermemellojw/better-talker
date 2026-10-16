package com.bettertalker.app.data.llm

import android.content.Context
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Decisão única de provider: **APENAS DeepSeek (BYOD)**.
 *
 * A UI nunca instancia provider direto. Sem chave, [useRemoteRoute] é falso
 * e o chat segue no motor determinístico — nunca um fallback silencioso.
 * (Os demais providers foram removidos no cleanup multi-LLM; a decisão
 * fica concentrada aqui.)
 */
object ProviderFactory {

    /** Id do único provider remoto suportado. */
    const val PROVIDER_DEEPSEEK = "deepseek"

    /** Config resolvida: provider + chave + modelo (para a ViewModel). */
    data class RemoteConfig(
        val providerId: String = PROVIDER_DEEPSEEK,
        val apiKey: String,
        val model: String? = null,
    )

    /** Resolve a config das Settings (1 leitura). */
    suspend fun resolveRemote(settings: SettingsStore, context: Context? = null): RemoteConfig =
        resolveProvider(
            deepseekKey = settings.deepseekApiKey.first().orEmpty(),
            deepseekModel = settings.deepseekModel.first(),
        )

    /** Resolução pura/testável (só DeepSeek; sem fallback silencioso). */
    fun resolveProvider(
        deepseekKey: String,
        deepseekModel: String = DeepSeekProvider.MODEL_FLASH,
    ): RemoteConfig = RemoteConfig(PROVIDER_DEEPSEEK, deepseekKey, deepseekModel)

    /** Instancia o provider da config. */
    fun createFor(
        config: RemoteConfig,
        effort: String = DeepSeekProvider.REASONING_EFFORT,
    ): LlmProvider = DeepSeekProvider(
        apiKey = config.apiKey,
        model = config.model ?: DeepSeekProvider.MODEL_FLASH,
        reasoningEffort = effort,
    )

    /**
     * Assinatura com contexto preservada por compatibilidade dos call sites
     * (o DeepSeek não precisa de Context). #17-fix: `effort` vem do chip
     * (Rápido/Profundo) nas rotas criativas.
     */
    fun createFor(
        config: RemoteConfig,
        context: Context,
        effort: String = DeepSeekProvider.REASONING_EFFORT,
    ): LlmProvider = createFor(config, effort)

    /** Provider com as Settings atuais. */
    suspend fun create(settings: SettingsStore): LlmProvider = createFor(resolveRemote(settings))

    /** Rota nova ativa? Só com chave configurada. Puro/testável. */
    fun useRemoteRoute(apiKey: String?): Boolean = !apiKey.isNullOrBlank()

    /** Idem, para a config resolvida. */
    fun useRemoteRoute(config: RemoteConfig): Boolean = useRemoteRoute(config.apiKey)
}
