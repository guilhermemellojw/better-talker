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

    /** T3 — decisão automática: remoto com chave e online → local → determinístico. */
    const val PROVIDER_AUTO = "auto"

    /** T3 (DeepSeek) — provider remoto BYOD com streaming SSE. */
    const val PROVIDER_DEEPSEEK = "deepseek"

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
    data class RemoteConfig(val providerId: String, val apiKey: String, val model: String? = null)

    /**
     * F20-F1/T3 — resolve provider+chave das Settings numa leitura só.
     * Com "auto", consulta a presença do modelo quando o [context] é dado
     * (UI/roteamento) e a conectividade; sem contexto, assume online e
     * resolve só pela ordem de chaves.
     */
    suspend fun resolveRemote(settings: SettingsStore, context: Context? = null): RemoteConfig {
        val selected = settings.llmProvider.first()
        val geminiKey = settings.llmApiKey.first().orEmpty()
        val groqKey = settings.groqApiKey.first().orEmpty()
        val deepseekKey = settings.deepseekApiKey.first().orEmpty()
        val deepseekModel = settings.deepseekModel.first()
        val modelPresent = selected == PROVIDER_AUTO && context != null &&
            LitertGemmaEngine.isModelPresent(context)
        return resolveProvider(
            selected = selected,
            modelPresent = modelPresent,
            geminiKey = geminiKey,
            groqKey = groqKey,
            deepseekKey = deepseekKey,
            online = context == null || isOnline(context),
            deepseekModel = deepseekModel,
        )
    }

    /** T3 — conectividade para a ordem do "auto" (offline => local/determinístico). */
    private fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
    } catch (_: Exception) {
        true
    }

    /**
     * T3 — resolução pura/testável. Ordem do "auto" (DeepSeek):
     * DeepSeek (chave + online) → Gemini (chave + online) → Groq/Qwen
     * (chave + online) → Gemma local (modelo presente) → determinístico
     * (Gemini sem chave ⇒ `useRemoteRoute` falso).
     *
     * Explícitos mantêm o comportamento anterior (sem checagem de rede: o
     * provider falha honesto se estiver offline).
     */
    fun resolveProvider(
        selected: String,
        modelPresent: Boolean,
        geminiKey: String,
        groqKey: String,
        deepseekKey: String = "",
        online: Boolean = true,
        deepseekModel: String = DeepSeekProvider.MODEL_FLASH,
    ): RemoteConfig = when {
        selected == PROVIDER_DEEPSEEK -> RemoteConfig(PROVIDER_DEEPSEEK, deepseekKey, deepseekModel)
        selected == PROVIDER_QWEN -> RemoteConfig(PROVIDER_QWEN, groqKey)
        // local não tem chave; a rota vale mesmo com chave vazia.
        selected == PROVIDER_LOCAL_GEMMA -> RemoteConfig(PROVIDER_LOCAL_GEMMA, "")
        selected == PROVIDER_AUTO && online && deepseekKey.isNotBlank() ->
            RemoteConfig(PROVIDER_DEEPSEEK, deepseekKey, deepseekModel)
        selected == PROVIDER_AUTO && online && geminiKey.isNotBlank() ->
            RemoteConfig(PROVIDER_GEMINI, geminiKey)
        selected == PROVIDER_AUTO && online && groqKey.isNotBlank() ->
            RemoteConfig(PROVIDER_QWEN, groqKey)
        selected == PROVIDER_AUTO && modelPresent -> RemoteConfig(PROVIDER_LOCAL_GEMMA, "")
        selected == PROVIDER_AUTO -> RemoteConfig(PROVIDER_GEMINI, "")
        else -> RemoteConfig(PROVIDER_GEMINI, geminiKey)
    }

    /** Instancia o provider da config resolvida (puro/testável). */
    fun createFor(config: RemoteConfig): LlmProvider = when (config.providerId) {
        PROVIDER_QWEN -> QwenProvider(apiKey = config.apiKey)
        PROVIDER_DEEPSEEK -> DeepSeekProvider(
            apiKey = config.apiKey,
            model = config.model ?: DeepSeekProvider.MODEL_FLASH,
        )
        else -> GeminiProvider(apiKey = config.apiKey)
    }

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
     * Cria um FallbackLlmProvider lendo as três chaves (DeepSeek, Groq, Gemini).
     *
     * Ordem: DeepSeek → Qwen (Groq) → Gemini.
     * - Só DeepSeek configurada: fallback com 1 provider (DeepSeekProvider)
     * - Nenhuma: GeminiProvider offline (não quebra contrato)
     */
    suspend fun createFallback(settings: SettingsStore): LlmProvider {
        val deepseekKey = settings.deepseekApiKey.first().orEmpty()
        val groqKey = settings.groqApiKey.first().orEmpty()
        val geminiKey = settings.llmApiKey.first().orEmpty()
        val deepseekModel = settings.deepseekModel.first()
        val providers = buildList {
            if (deepseekKey.isNotBlank()) {
                add(DeepSeekProvider(apiKey = deepseekKey, model = deepseekModel))
            }
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
