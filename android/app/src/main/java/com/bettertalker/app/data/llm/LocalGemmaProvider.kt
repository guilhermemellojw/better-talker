package com.bettertalker.app.data.llm

import android.content.Context
import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.llm.litert.LitertGemmaEngine
import kotlinx.coroutines.CancellationException

/**
 * F2 — provider on-device (Gemma 4 E2B / LiteRT-LM).
 * F2.1 — publica fases semânticas ([LocalProgress]), compacta o RAG
 * ([LeanRag]) e reforça grounding, sem mudar o contrato.
 *
 * Reaproveita o MESMO [buildProviderPrompts] dos providers remotos (paridade
 * de contrato: mesmo LlmRequest ⇒ mesmo prompt) e aplica o
 * [GroundednessVerifier] após a geração — remoção conservadora de frases sem
 * apoio + aviso discreto.
 *
 * Sem chave, sem rede: `offline = true`. Modelo ausente ⇒ erro honesto
 * UNAVAILABLE (o chat decide o fallback; nunca há queda silenciosa para nuvem).
 */
class LocalGemmaProvider(
    private val context: Context?,
    private val engine: GemmaEngine = LitertGemmaEngine.get(context!!),
    private val modelPresent: () -> Boolean =
        { context?.let { LitertGemmaEngine.isModelPresent(it) } ?: false },
    override val model: String = LitertGemmaEngine.MODEL_FILE,
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) },
) : LlmProvider {

    override val id: String = "gemma_local"

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val started = System.currentTimeMillis()
        LocalProgress.reset()
        if (!modelPresent()) {
            throw ProviderError(
                ProviderErrorCode.UNAVAILABLE,
                "Modelo local ausente (files/models/${LitertGemmaEngine.MODEL_FILE}).",
                id, 0,
            )
        }
        LocalProgress.set(LocalPhase.LoadingModel)
        val tLoad0 = System.currentTimeMillis()
        try {
            engine.warmup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("gemma_local falhou no load: ${e.message}")
            throw ProviderError(ProviderErrorCode.UNAVAILABLE, "Gemma local indisponível.", id, 1)
        }
        val loadMs = System.currentTimeMillis() - tLoad0

        LocalProgress.set(LocalPhase.ReadingSources)
        val leanRequest = LeanRag.compact(request.contextPack)?.let { request.copy(contextPack = it) }
            ?: request
        val prompts = buildProviderPrompts(leanRequest)
        val system = listOf(prompts.first, GEMMA_GROUNDING_BLOCK)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        LocalProgress.set(LocalPhase.Generating)
        val raw: String
        val ttftMs: Long
        val genMs: Long
        try {
            val sb = StringBuilder()
            var firstAt = -1L
            val tGen0 = System.currentTimeMillis()
            engine.generate(system, prompts.second, request.maxOutputTokens)
                .collect { chunk ->
                    if (firstAt < 0) firstAt = System.currentTimeMillis()
                    sb.append(chunk)
                    LocalProgress.appendPartial(chunk)
                }
            raw = sb.toString()
            ttftMs = if (firstAt < 0) -1 else firstAt - tGen0
            genMs = System.currentTimeMillis() - tGen0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("gemma_local falhou na geração: ${e.message}")
            throw ProviderError(ProviderErrorCode.UNAVAILABLE, "Gemma local indisponível.", id, 1)
        }

        val verified = GroundednessVerifier.verify(raw, contentSources(leanRequest))
        if (verified.hasRemovals) {
            log("gemma_local verificador removeu ${verified.removed.size} trecho(s) sem apoio")
        }
        LocalProgress.set(LocalPhase.Done)
        val estTokens = LeanRag.estimateTokens(raw)
        val packTokens = estimatePackTokens(leanRequest)
        val dossierTokens = leanRequest.contextBlock?.let { LeanRag.estimateTokens(it) } ?: 0
        log("gemma_local ok load=${loadMs}ms ttft=${ttftMs}ms gen=${genMs}ms " +
            "rag~${packTokens}tok dossier~${dossierTokens}tok out~${estTokens}tok")
        return LlmResponse(
            verified.text,
            LlmResponseMeta(
                providerId = id,
                model = model,
                durationMs = System.currentTimeMillis() - started,
                attempts = 1,
                offline = true,
            ),
        )
    }

    /** Corpus do verificador: fontes de conteúdo + trechos legados + dossiê.
     *
     * F2.2: o dossiê (contexto da seção em foco) É material de fonte exibido
     * ao modelo — sem ele aqui, uma citação fiel à seção seria removida
     * injustamente. */
    private fun contentSources(request: LlmRequest): List<String> {
        val content = request.contextPack?.contentSources
            ?.map { "${it.reference} ${it.text}" }
            .orEmpty()
        val dossier = request.contextBlock?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        return content + request.contextPassages + dossier
    }

    /** Só RetrievalRepository + legados (sem dossiê) — medida honesta do RAG. */
    private fun estimatePackTokens(request: LlmRequest): Int {
        val pack = request.contextPack
        val packTokens = pack?.let {
            it.contentSources.sumOf { s -> LeanRag.sourceTokens(s) } +
                it.trainingSources.sumOf { s -> LeanRag.sourceTokens(s) }
        } ?: 0
        return packTokens + request.contextPassages.sumOf { LeanRag.estimateTokens(it) }
    }
}
