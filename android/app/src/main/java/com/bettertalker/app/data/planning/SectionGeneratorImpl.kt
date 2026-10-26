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
import com.bettertalker.app.domain.planning.LONG_TEXT_MAX_OUTPUT_TOKENS
import com.bettertalker.app.domain.planning.LongTextKind
import com.bettertalker.app.domain.planning.LongTextRequest
import com.bettertalker.app.domain.planning.LongTextScope
import com.bettertalker.app.domain.planning.longTextKindOf
import com.bettertalker.app.data.copilot.generationTelemetry
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
        val kind = longTextKindOf(dossier.discourseType)
        val minutes = dossier.currentSection.minutes
        val budget = draftOutputBudget(minutes, kind)
        // Fase A: persona pelo tipo persistido — MINISTRY_TALK é discurso.
        val profile = GenerationProfile.forDiscourseType(dossier.discourseType)
        val first = callLlm(prompt, budget, profile, minutes, kind) ?: return null
        val firstParsed = parse(first) ?: return null
        val firstCut = looksTruncated(firstParsed.textHtml, first.meta.finishReason)
        logTelemetry("draft", first, firstParsed.textHtml, truncated = firstCut)
        if (!firstCut) {
            return draftOf(firstParsed, dossier, truncated = false)
        }
        // Retry 1x com instrução reforçada (nunca mais de 1 — cota).
        val retryPrompt = prompt + "\n\n## ATENÇÃO\n" +
            "A resposta anterior foi cortada antes do fim. Escreva o " +
            "rascunho completo, encerrando com ponto final. Não pare no meio."
        val second = callLlm(retryPrompt, budget, profile, minutes, kind)
            ?: return draftOf(firstParsed, dossier, truncated = true)
        val secondParsed = parse(second)
            ?: return draftOf(firstParsed, dossier, truncated = true)
        val secondCut = looksTruncated(secondParsed.textHtml, second.meta.finishReason)
        logTelemetry("draft-retry", second, secondParsed.textHtml, truncated = secondCut)
        if (!secondCut) {
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
        minutes: Int,
        kind: LongTextKind,
    ): LlmResponse? {
        val llmRequest = LlmRequest(
            text = prompt,
            responseFormat = ResponseFormat.JSON_SCHEMA,
            jsonSchema = SECTION_DRAFT_SCHEMA,
            maxAttempts = 1, // sem retry automático — evita queimar cota em 429
            timeoutMs = 30_000L,
            maxOutputTokens = maxOutputTokens,
            generationProfile = profile,
            longTextRequest = minutes.takeIf { it > 0 }?.let {
                LongTextRequest(
                    kind = kind,
                    scope = LongTextScope.SECTION,
                    minutes = it,
                    sectionMinutes = it,
                )
            },
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

    private fun logTelemetry(
        route: String,
        response: LlmResponse,
        text: String,
        truncated: Boolean,
    ) {
        log(
            generationTelemetry(
                route = route,
                text = text,
                budget = response.meta.budget,
                finishReason = response.meta.finishReason,
                truncated = truncated,
                thinking = response.meta.thinking,
            ).line()
        )
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
 * Orçamento de saída do draft proporcional ao tempo da SEÇÃO (nunca ao total
 * do discurso). Usa a mesma fórmula do texto longo (palavras × 1,5 + 200 ou
 * réplicas × 80 + 200), com piso 2048 e teto 8192. Acima de 8192 o chamador
 * deve dividir por sub-ponto/seção. Puro/testável.
 */
internal fun draftOutputBudget(
    minutes: Int?,
    kind: LongTextKind = LongTextKind.SPEECH,
): Int {
    val safe = minutes?.takeIf { it > 0 } ?: return 500
    val raw = if (kind == LongTextKind.DEMO) {
        com.bettertalker.app.domain.planning.SpeechPacing.targetTurns(safe) * 80 + 200
    } else {
        com.bettertalker.app.domain.planning.SpeechPacing.targetWords(safe) * 3 / 2 + 200
    }
    return raw.coerceIn(2048, LONG_TEXT_MAX_OUTPUT_TOKENS)
}
