package com.bettertalker.app.data.llm

import android.content.Context
import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.llm.litert.LitertGemmaEngine
import kotlinx.coroutines.CancellationException

/**
 * F2 — provider on-device (Gemma 4 E2B / LiteRT-LM).
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
    private val context: Context,
    private val engine: LitertGemmaEngine = LitertGemmaEngine.get(context),
    override val model: String = LitertGemmaEngine.MODEL_FILE,
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) },
) : LlmProvider {

    override val id: String = "gemma_local"

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val started = System.currentTimeMillis()
        if (!LitertGemmaEngine.isModelPresent(context)) {
            throw ProviderError(
                ProviderErrorCode.UNAVAILABLE,
                "Modelo local ausente (files/models/${LitertGemmaEngine.MODEL_FILE}).",
                id, 0,
            )
        }
        val prompts = buildProviderPrompts(request)
        val raw = try {
            val sb = StringBuilder()
            engine.generate(prompts.first, prompts.second, request.maxOutputTokens)
                .collect { sb.append(it) }
            sb.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("gemma_local falhou: ${e.message}")
            throw ProviderError(ProviderErrorCode.UNAVAILABLE, "Gemma local indisponível.", id, 1)
        }

        val verified = GroundednessVerifier.verify(raw, contentSources(request))
        if (verified.hasRemovals) {
            log("gemma_local verificador removeu ${verified.removed.size} trecho(s) sem apoio")
        }
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

    /** Corpus do verificador: fontes de conteúdo + trechos legados. */
    private fun contentSources(request: LlmRequest): List<String> {
        val content = request.contextPack?.contentSources
            ?.map { "${it.reference} ${it.text}" }
            .orEmpty()
        return content + request.contextPassages
    }
}
