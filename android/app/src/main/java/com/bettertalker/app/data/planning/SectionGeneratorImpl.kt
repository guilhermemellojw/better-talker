package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DossierFidelityCheck
import com.bettertalker.app.domain.planning.SectionDraft
import com.bettertalker.app.domain.planning.SectionGenerator
import kotlinx.coroutines.CancellationException

/**
 * Implementação real: Dossier → prompt → LLM (JSON_SCHEMA) → parser →
 * fidelity check → SectionDraft.
 *
 * Falhas tratáveis (rate limit, JSON inválido, texto vazio, exceção
 * genérica) → null.
 * CancellationException propaga (structured concurrency — padrão 1.3b).
 *
 * Modelo: OutlineGeneratorImpl (data/planning).
 */
class SectionGeneratorImpl(
    private val llmProvider: LlmProvider,
    private val promptBuilder: DossierPromptBuilder = DefaultDossierPromptBuilder(),
    private val responseParser: SectionDraftParser = DefaultSectionDraftParser(),
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) },
) : SectionGenerator {

    override suspend fun generate(dossier: Dossier): SectionDraft? {
        val prompt = promptBuilder.build(dossier)
        val llmRequest = LlmRequest(
            text = prompt,
            responseFormat = ResponseFormat.JSON_SCHEMA,
            jsonSchema = SECTION_DRAFT_SCHEMA,
            maxAttempts = 1, // sem retry automático — evita queimar cota em 429
            timeoutMs = 30_000L,
            maxOutputTokens = 500, // alvo maior (BODY ~250 palavras) + JSON cabem; o teto é safety net
        )
        val response = try {
            llmProvider.generate(llmRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Diagnóstico interno; o usuário segue vendo só o texto do sheet.
            log("draft falhou: ${e::class.simpleName}: ${e.message}")
            return null
        }
        if (response.text.isBlank()) return null
        val parsed = responseParser.parse(response.text, response.meta.finishReason) ?: return null
        val fidelity = DossierFidelityCheck.check(
            textHtml = parsed.textHtml,
            usedSources = parsed.usedSources,
            dossier = dossier,
        )
        return SectionDraft(
            textHtml = parsed.textHtml,
            usedSources = parsed.usedSources,
            validation = fidelity,
            possiblyTruncated = parsed.possiblyTruncated,
        )
    }

    internal companion object {
        /**
         * Schema do draft. Envelope estrito (Groq) espelhando OUTLINE_SCHEMA.
         * O modo estrito exige `additionalProperties:false` em TODO objeto,
         * senão o Groq rejeita o request inteiro (HTTP 400).
         */
        const val SECTION_DRAFT_SCHEMA: String =
            "{\"name\":\"section_draft\",\"strict\":true,\"schema\":" +
                "{\"type\":\"object\",\"properties\":" +
                "{\"text\":{\"type\":\"string\"}," +
                "\"usedSources\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}," +
                "\"required\":[\"text\",\"usedSources\"]," +
                "\"additionalProperties\":false}}"
    }
}
