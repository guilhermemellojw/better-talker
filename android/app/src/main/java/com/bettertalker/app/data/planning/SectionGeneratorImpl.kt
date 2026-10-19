package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DossierFidelityCheck
import com.bettertalker.app.domain.planning.GenerationProfile
import com.bettertalker.app.domain.planning.SectionDraft
import com.bettertalker.app.domain.planning.SectionGenerator
import com.bettertalker.app.domain.planning.SpeechPacing
import kotlinx.coroutines.CancellationException

/**
 * Implementação real: Dossier → prompt → LLM (JSON_SCHEMA) → parser →
 * fidelity check → SectionDraft.
 *
 * Falhas tratáveis (rate limit, JSON inválido, texto vazio, exceção
 * genérica) → null.
 * CancellationException propaga (structured concurrency — padrão 1.3b).
 *
 * Corte do modelo: o `qwen/qwen3-8b` emite `stop` prematuro em
 * structured output (~85 palavras, com folga no orçamento). Por isso
 * há retry 1x com instrução reforçada quando [looksTruncated] detecta
 * corte; se o retry também cortar, o texto é truncado até a última
 * frase completa e marcado ([SectionDraft.possiblyTruncated]).
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
        val budget = draftOutputBudget(dossier.currentSection.minutes)
        // Fase A: persona pelo tipo persistido — MINISTRY_TALK é discurso.
        val profile = GenerationProfile.forDiscourseType(dossier.discourseType)
        val first = callLlm(prompt, budget, profile) ?: return null
        val firstParsed = parse(first) ?: return null
        if (!looksTruncated(firstParsed.textHtml, first.meta.finishReason)) {
            return draftOf(firstParsed, dossier, truncated = false)
        }
        // Retry 1x com instrução reforçada (nunca mais de 1 — cota).
        val retryPrompt = prompt + "\n\n## ATENÇÃO\n" +
            "A resposta anterior foi cortada antes do fim. Escreva o " +
            "rascunho completo, encerrando com ponto final. Não pare no meio."
        val second = callLlm(retryPrompt, budget, profile)
            ?: return draftOf(firstParsed, dossier, truncated = true)
        val secondParsed = parse(second)
            ?: return draftOf(firstParsed, dossier, truncated = true)
        if (!looksTruncated(secondParsed.textHtml, second.meta.finishReason)) {
            return draftOf(secondParsed, dossier, truncated = false)
        }
        // Truncar até a última frase completa; sem pontuação, devolve
        // como está (marcado).
        val cut = secondParsed.textHtml.lastIndexOfAny(
            charArrayOf('.', '!', '?', '…')
        )
        val clean = if (cut > 0) {
            secondParsed.textHtml.substring(0, cut + 1)
        } else {
            secondParsed.textHtml
        }
        return draftOf(secondParsed.copy(textHtml = clean), dossier, truncated = true)
    }

    private suspend fun callLlm(
        prompt: String,
        maxOutputTokens: Int,
        profile: GenerationProfile,
    ): LlmResponse? {
        val llmRequest = LlmRequest(
            text = prompt,
            responseFormat = ResponseFormat.JSON_SCHEMA,
            jsonSchema = SECTION_DRAFT_SCHEMA,
            maxAttempts = 1, // sem retry automático — evita queimar cota em 429
            timeoutMs = 30_000L,
            maxOutputTokens = maxOutputTokens,
            generationProfile = profile,
        )
        return try {
            llmProvider.generate(llmRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Diagnóstico interno; o usuário segue vendo só o texto do sheet.
            log("draft falhou: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parse(response: LlmResponse): ParsedDraft? {
        if (response.text.isBlank()) return null
        return responseParser.parse(response.text, response.meta.finishReason)
    }

    private fun draftOf(
        parsed: ParsedDraft,
        dossier: Dossier,
        truncated: Boolean,
    ): SectionDraft {
        val fidelity = DossierFidelityCheck.check(
            textHtml = parsed.textHtml,
            usedSources = parsed.usedSources,
            dossier = dossier,
        )
        return SectionDraft(
            textHtml = parsed.textHtml,
            usedSources = parsed.usedSources,
            validation = fidelity,
            possiblyTruncated = truncated,
        )
    }

    internal companion object {
        /**
         * Schema do draft. Envelope estrito, espelhando OUTLINE_SCHEMA.
         * O modo estrito exige `additionalProperties:false` em TODO objeto,
         * senão o endpoint rejeita o request inteiro (HTTP 400).
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

/**
 * P0 (alinhamento persona/tempo): orçamento de saída proporcional à meta de
 * palavras da seção (~130 palavras/min; ~1,45 tokens/palavra + folga do
 * JSON). Piso 500 (comportamento antigo) e teto 1280 — o mesmo comprovado
 * no fluxo oratório (2048 sempre toma 429 no OTPM 1000/min do plano
 * gratuito; 1280 nunca truncou em execuções reais). Puro/testável.
 */
internal fun draftOutputBudget(minutes: Int?): Int {
    val words = minutes?.takeIf { it > 0 }?.let { SpeechPacing.targetWords(it) } ?: 0
    val tokens = words * 145 / 100 + 120
    return tokens.coerceIn(500, 1280)
}
