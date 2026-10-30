package com.bettertalker.app.data.planning

import com.bettertalker.app.data.copilot.generationTelemetry
import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DossierFidelityCheck
import com.bettertalker.app.domain.planning.DraftPartSpec
import com.bettertalker.app.domain.planning.DraftProgress
import com.bettertalker.app.domain.planning.GenerationProfile
import com.bettertalker.app.domain.planning.LongTextKind
import com.bettertalker.app.domain.planning.LongTextRequest
import com.bettertalker.app.domain.planning.LongTextScope
import com.bettertalker.app.domain.planning.OutputBudget
import com.bettertalker.app.domain.planning.SectionDraft
import com.bettertalker.app.domain.planning.SectionDraftMode
import com.bettertalker.app.domain.planning.SectionDraftPlan
import com.bettertalker.app.domain.planning.SectionGenerator
import com.bettertalker.app.domain.planning.SpeechPacing
import com.bettertalker.app.domain.planning.cardWordCount
import com.bettertalker.app.domain.planning.longTextKindOf
import com.bettertalker.app.domain.planning.sectionDraftPlan
import kotlinx.coroutines.CancellationException

/**
 * Implementação real: Dossier → prompt → LLM (JSON_SCHEMA) → parser →
 * fidelity check → SectionDraft.
 *
 * M3/45 min: BODY sem subpontos que não cabe em uma resposta é gerado em
 * partes (CONTINUATION). Cada parte recebe minutos/meta próprios; o stitch
 * remove parágrafo repetido e corta na última frase completa. A fidelidade
 * é conferida no texto FINAL, não em cada parte.
 *
 * Falhas tratáveis (rate limit, JSON inválido, texto vazio, exceção
 * genérica) → null.
 * CancellationException propaga (structured concurrency — padrão 1.3b).
 *
 * Corte do modelo: o retry 1x por parte continua; nunca faz loop.
 */
class SectionGeneratorImpl(
    private val llmProvider: LlmProvider,
    private val promptBuilder: DossierPromptBuilder = DefaultDossierPromptBuilder(),
    private val responseParser: SectionDraftParser = DefaultSectionDraftParser(),
    private val log: (String) -> Unit = { android.util.Log.w("CopilotLLM", it) },
) : SectionGenerator {

    override suspend fun generate(dossier: Dossier): SectionDraft? =
        generateWithProgress(dossier) {}

    override suspend fun generateWithProgress(
        dossier: Dossier,
        onProgress: (DraftProgress) -> Unit,
    ): SectionDraft? {
        val kind = longTextKindOf(dossier.discourseType)
        val sectionMinutes = dossier.currentSection.minutes
        val hasSubPoints = dossier.sectionSubPoints.isNotEmpty()
        // C1: sub-ponto de seção longa usa a fatia; BODY inteiro usa a seção.
        val partMinutes = effectiveDraftMinutes(
            sectionMinutes = sectionMinutes,
            isSubPoint = dossier.currentSubPoint != null,
            subPointCount = dossier.sectionSubPoints.size,
        )
        val profile = GenerationProfile.forDiscourseType(dossier.discourseType)
        val sectionRequest = LongTextRequest(
            kind = kind,
            scope = LongTextScope.SECTION,
            minutes = sectionMinutes.coerceAtLeast(1),
            sectionMinutes = sectionMinutes,
        )
        val plan = sectionDraftPlan(
            role = dossier.currentSection.role,
            sectionMinutes = sectionMinutes,
            kind = kind,
            hasSubPoints = hasSubPoints,
            subPointCount = dossier.sectionSubPoints.size,
            budget = OutputBudget.longText(sectionRequest),
        )
        if (plan.mode == SectionDraftMode.CONTINUATION) {
            return generateContinuation(
                dossier = dossier,
                plan = plan,
                kind = kind,
                sectionMinutes = sectionMinutes,
                profile = profile,
                onProgress = onProgress,
            )
        }
        return generateSingle(
            dossier = dossier,
            kind = kind,
            partMinutes = partMinutes,
            sectionMinutes = sectionMinutes,
            profile = profile,
        )
    }

    /** Caminho atual (INTRO/CONCLUSION, 30 min, sub-ponto, demo). */
    private suspend fun generateSingle(
        dossier: Dossier,
        kind: LongTextKind,
        partMinutes: Int,
        sectionMinutes: Int,
        profile: GenerationProfile,
    ): SectionDraft? {
        val prompt = promptBuilder.build(dossier)
        val budget = OutputBudget.sectionDraft(partMinutes, kind)
        val request = LongTextRequest(
            kind = kind,
            scope = LongTextScope.SECTION,
            minutes = partMinutes,
            sectionMinutes = sectionMinutes,
        )
        val timeout = timeoutFor(partMinutes)
        val first = callLlm(prompt, budget, profile, request, timeout) ?: return null
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
        val second = callLlm(retryPrompt, budget, profile, request, timeout)
            ?: return draftOf(firstParsed, dossier, truncated = true)
        val secondParsed = parse(second)
            ?: return draftOf(firstParsed, dossier, truncated = true)
        val secondCut = looksTruncated(secondParsed.textHtml, second.meta.finishReason)
        logTelemetry("draft-retry", second, secondParsed.textHtml, truncated = secondCut)
        if (!secondCut) {
            return draftOf(secondParsed, dossier, truncated = false)
        }
        val clean = cutAtLastCompleteSentence(secondParsed.textHtml)
        return draftOf(secondParsed.copy(textHtml = clean), dossier, truncated = true)
    }

    /**
     * M3: partes internas do BODY monolítico. A parte 1 não conclui; as
     * seguintes continuam do tail e a última conclui. O texto final é único.
     */
    private suspend fun generateContinuation(
        dossier: Dossier,
        plan: SectionDraftPlan,
        kind: LongTextKind,
        sectionMinutes: Int,
        profile: GenerationProfile,
        onProgress: (DraftProgress) -> Unit,
    ): SectionDraft? {
        val texts = mutableListOf<String>()
        val used = mutableSetOf<String>()
        var wordsSoFar = 0
        var lastIncludedCut = false
        for (i in plan.partMinutes.indices) {
            val index = i + 1
            val partMinutes = plan.partMinutes[i]
            val targetWords = plan.partTargetWords[i]
            onProgress(
                DraftProgress(
                    partIndex = index,
                    partCount = plan.parts,
                    targetWords = targetWords,
                    wordsSoFar = wordsSoFar,
                )
            )
            val spec = DraftPartSpec(
                index = index,
                count = plan.parts,
                targetWords = targetWords,
                previousTail = texts.lastOrNull()?.takeLast(500),
            )
            val prompt = promptBuilder.buildPart(dossier, spec)
            val request = LongTextRequest(
                kind = kind,
                scope = LongTextScope.SECTION,
                minutes = partMinutes,
                sectionMinutes = sectionMinutes,
                targetWords = targetWords,
                partIndex = index,
                partCount = plan.parts,
            )
            val budget = OutputBudget.longText(request)
            val response = callLlm(
                prompt = prompt,
                maxOutputTokens = budget.maxTokens,
                profile = profile,
                longTextRequest = request,
                timeoutMs = timeoutFor(partMinutes),
            )
            val firstParsed = response?.let { parsePart(it) }
            if (firstParsed == null) {
                if (texts.isEmpty()) return null
                break
            }
            var parsed = firstParsed
            var text = parsed.textHtml
            var cut = parsed.possiblyTruncated ||
                looksTruncated(text, response.meta.finishReason)
            logTelemetry("draft-part-$index", response, text, truncated = cut)
            if (cut) {
                val retryPrompt = prompt + "\n\n## ATENÇÃO\n" +
                    "A parte anterior foi cortada antes do fim. " +
                    if (index == plan.parts) {
                        "Escreva a parte completa e conclua a seção."
                    } else {
                        "Escreva a parte completa, sem concluir a seção."
                    }
                val retry = callLlm(
                    prompt = retryPrompt,
                    maxOutputTokens = budget.maxTokens,
                    profile = profile,
                    longTextRequest = request,
                    timeoutMs = timeoutFor(partMinutes),
                )
                val retryParsed = retry?.let { parsePart(it) }
                if (retryParsed != null) {
                    parsed = retryParsed
                    text = retryParsed.textHtml
                    cut = retryParsed.possiblyTruncated ||
                        looksTruncated(text, retry.meta.finishReason)
                    logTelemetry(
                        "draft-part-$index-retry",
                        retry,
                        text,
                        truncated = cut,
                    )
                }
            }
            if (cut) text = cutAtLastCompleteSentence(text)
            texts += text
            used += parsed.usedSources
            lastIncludedCut = cut
            wordsSoFar = cardWordCount(stitchSectionParts(texts, cutLast = false))
        }
        if (texts.isEmpty()) return null
        val stitched = stitchSectionParts(texts, cutLast = lastIncludedCut)
        val totalWords = cardWordCount(stitched)
        val truncated = lastIncludedCut ||
            totalWords < (plan.totalTargetWords * 80 / 100)
        return draftOf(stitched, used.toList(), dossier, truncated)
    }

    /**
     * "Continuar geração" no DraftSheet: estende o texto atual (curto/cortado)
     * com uma parte final, sem repetir o que já existe.
     */
    override suspend fun continueDraft(
        dossier: Dossier,
        currentText: String,
        currentUsedSources: List<String>,
        targetWords: Int,
    ): SectionDraft? {
        val kind = longTextKindOf(dossier.discourseType)
        val sectionMinutes = dossier.currentSection.minutes
        val currentWords = cardWordCount(currentText)
        val remaining = (targetWords - currentWords).coerceAtLeast(300)
        val partMinutes = ((remaining + SpeechPacing.WORDS_PER_MINUTE - 1) /
            SpeechPacing.WORDS_PER_MINUTE).coerceAtLeast(1)
        val spec = DraftPartSpec(
            index = 2,
            count = 2,
            targetWords = remaining,
            previousTail = currentText,
        )
        val prompt = promptBuilder.buildPart(dossier, spec)
        val request = LongTextRequest(
            kind = kind,
            scope = LongTextScope.SECTION,
            minutes = partMinutes,
            sectionMinutes = sectionMinutes,
            targetWords = remaining,
            partIndex = 2,
            partCount = 2,
        )
        val profile = GenerationProfile.forDiscourseType(dossier.discourseType)
        val budget = OutputBudget.longText(request)
        val response = callLlm(
            prompt = prompt,
            maxOutputTokens = budget.maxTokens,
            profile = profile,
            longTextRequest = request,
            timeoutMs = timeoutFor(partMinutes),
        ) ?: return null
        val parsed = parsePart(response) ?: return null
        var text = parsed.textHtml
        val cut = parsed.possiblyTruncated ||
            looksTruncated(text, response.meta.finishReason)
        if (cut) text = cutAtLastCompleteSentence(text)
        val stitched = stitchSectionParts(listOf(currentText, text), cutLast = cut)
        val total = cardWordCount(stitched)
        val truncated = cut || (targetWords > 0 && total < targetWords * 80 / 100)
        return draftOf(
            textHtml = stitched,
            usedSources = (currentUsedSources + parsed.usedSources).distinct(),
            dossier = dossier,
            truncated = truncated,
        )
    }

    private suspend fun callLlm(
        prompt: String,
        maxOutputTokens: Int,
        profile: GenerationProfile,
        longTextRequest: LongTextRequest?,
        timeoutMs: Long = 30_000L,
    ): LlmResponse? {
        val llmRequest = LlmRequest(
            text = prompt,
            responseFormat = ResponseFormat.JSON_SCHEMA,
            jsonSchema = SECTION_DRAFT_SCHEMA,
            // 1 tentativa + 1 retry de qualidade (length/extensão). O
            // retry por length sobe o budget até o cap (4.488 → 8.192);
            // sem isso o provider registrava o retry e desistia.
            maxAttempts = 2,
            timeoutMs = timeoutMs,
            maxOutputTokens = maxOutputTokens,
            maxOutputTokensCap = OutputBudget.DRAFT_MAX,
            generationProfile = profile,
            longTextRequest = longTextRequest,
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

    private fun timeoutFor(minutes: Int): Long =
        if (minutes >= 20) 180_000L else 30_000L

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
                attempts = response.meta.attempts,
                retries = response.meta.retries,
            ).line()
        )
    }

    private fun parse(response: LlmResponse): ParsedDraft? {
        if (response.text.isBlank()) return null
        return responseParser.parse(response.text, response.meta.finishReason)
    }

    /** Parte: aceita JSON estrito; se cortou, salva o `text` parcial. */
    private fun parsePart(response: LlmResponse): ParsedDraft? =
        parse(response) ?: if (response.text.isBlank()) {
            null
        } else {
            responseParser.parsePartial(response.text, response.meta.finishReason)
                ?.also { log("draft partial recuperado: ${it.textHtml.length} chars") }
        }

    private fun draftOf(
        parsed: ParsedDraft,
        dossier: Dossier,
        truncated: Boolean,
    ): SectionDraft = draftOf(parsed.textHtml, parsed.usedSources, dossier, truncated)

    private fun draftOf(
        textHtml: String,
        usedSources: List<String>,
        dossier: Dossier,
        truncated: Boolean,
    ): SectionDraft {
        val fidelity = DossierFidelityCheck.check(
            textHtml = textHtml,
            usedSources = usedSources,
            dossier = dossier,
        )
        return SectionDraft(
            textHtml = textHtml,
            usedSources = usedSources,
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
 * C1 — duração efetiva do alvo do draft. Um sub-ponto de uma seção longa usa
 * a fatia proporcional; a seção inteira usa section.minutes (nunca o total do
 * discurso). Puro/testável.
 */
internal fun effectiveDraftMinutes(
    sectionMinutes: Int,
    isSubPoint: Boolean,
    subPointCount: Int,
): Int {
    if (!isSubPoint || subPointCount <= 1 || sectionMinutes <= 0) return sectionMinutes
    return (sectionMinutes / subPointCount).coerceAtLeast(1)
}
