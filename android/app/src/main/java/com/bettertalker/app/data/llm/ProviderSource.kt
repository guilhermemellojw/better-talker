package com.bettertalker.app.data.llm

import android.content.Context
import com.bettertalker.app.data.prefs.SettingsStore

/**
 * BETA-TESTABILITY/P10 — seam mínima de injeção do provider.
 *
 * A implementação padrão delega EXATAMENTE a [ProviderFactory]: a rota
 * produtiva (DeepSeek BYOD, sem chave → motor determinístico) permanece
 * integralmente preservada. Implementações de teste podem capturar
 * `LlmRequest.history`, controlar streaming/cancelamento/falhas e provar
 * ausência de chamada — sem qualquer mecanismo de ativação na distribuição.
 */
interface ProviderSource {

    suspend fun resolveRemote(
        settings: SettingsStore,
        context: Context? = null,
    ): ProviderFactory.RemoteConfig

    fun createFor(
        config: ProviderFactory.RemoteConfig,
        context: Context? = null,
        effort: String = DeepSeekProvider.REASONING_EFFORT,
    ): LlmProvider

    fun useRemoteRoute(config: ProviderFactory.RemoteConfig): Boolean
}

/** Única implementação usada em produção. */
object DefaultProviderSource : ProviderSource {
    override suspend fun resolveRemote(
        settings: SettingsStore,
        context: Context?,
    ): ProviderFactory.RemoteConfig = ProviderFactory.resolveRemote(settings, context)

    override fun createFor(
        config: ProviderFactory.RemoteConfig,
        context: Context?,
        effort: String,
    ): LlmProvider = ProviderFactory.createFor(config, effort)

    override fun useRemoteRoute(config: ProviderFactory.RemoteConfig): Boolean =
        ProviderFactory.useRemoteRoute(config)
}
