package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatTurnContext
import com.bettertalker.app.data.copilot.ContentEligibilityGate
import com.bettertalker.app.data.copilot.FidelityFinding
import com.bettertalker.app.data.llm.GroundednessVerifier
import com.bettertalker.app.data.llm.VerificationCategory
import com.bettertalker.app.data.llm.VerificationMetrics
import com.bettertalker.app.data.llm.VerificationResult
import com.bettertalker.app.data.s34.S34Document

/**
 * #7e — corpus textual de um S-34 (objetivo, nota do orador, seções,
 * subseções e refs), no mesmo espírito do [verificationCorpus] do chat.
 * Usado nos caminhos de geração que não têm um [ChatTurnContext].
 * Puro/testável.
 */
internal fun s34StructuralContext(doc: S34Document): List<String> {
    val items = mutableListOf<com.bettertalker.app.data.copilot.EvidenceItem>()
    doc.objective?.let { if (it.isNotBlank()) items += structuralItem("objective", it) }
    doc.speakerNote?.let { if (it.isNotBlank()) items += structuralItem("speakerNote", it) }
    doc.headerLines.forEach { if (it.isNotBlank()) items += structuralItem("header", it) }
    doc.sections.forEach { sec ->
        if (sec.title.isNotBlank()) items += structuralItem("section-title:${sec.id}", sec.title)
        if (sec.content.isNotBlank()) items += structuralItem("section-content:${sec.id}", sec.content)
        sec.references.forEach { ref ->
            if (ref.rawText.isNotBlank()) {
                items += structuralItem("section-ref:${ref.rawText}", ref.rawText, resolved = false)
            }
        }
        sec.subsections.forEach { sub ->
            if (sub.content.isNotBlank()) items += structuralItem("subsection:${sub.id}", sub.content)
            sub.references.forEach { ref ->
                if (ref.rawText.isNotBlank()) {
                    items += structuralItem("subsection-ref:${ref.rawText}", ref.rawText, resolved = false)
                }
            }
        }
    }
    return com.bettertalker.app.data.copilot.EvidenceCorpus(items).structuralTexts()
}

private fun structuralItem(id: String, text: String, resolved: Boolean = true): com.bettertalker.app.data.copilot.EvidenceItem =
    com.bettertalker.app.data.copilot.EvidenceItem(
        id = id,
        reference = id,
        provenance = com.bettertalker.app.data.copilot.EvidenceProvenance(
            sourceId = id,
            category = com.bettertalker.app.data.copilot.EvidenceCategory.STRUCTURAL,
            recoveredText = text,
            resolved = resolved,
            waived = false,
        ),
    )

/**
 * #7e — gate de groundedness para textos GERADOS fora do chat (oratória e
 * "insira tudo"): mesmo verificador do chat, aplicado antes de devolver o
 * texto ao usuário.
 *
 * Corpus factual = [turnContext] + [documentarySources] elegíveis. Textos de
 * [structuralExtras] permanecem como contexto, nunca como prova.
 * Todo resultado passa pelo `ContentEligibilityGate` — com corpus vazio ou
 * não vazio — antes e depois das verificações complementares. Aspas abertas,
 * citações não fundadas e conteúdo resultante rejeitado produzem texto vazio
 * e aviso; explicações e paráfrases legítimas seguem utilizáveis. Puro/testável.
 */
internal fun verifyGeneratedText(
    text: String,
    turnContext: ChatTurnContext? = null,
    extraSources: List<String> = emptyList(),
    questionRefs: List<String> = emptyList(),
): VerificationResult = verifyGeneratedText(
    text = text,
    turnContext = turnContext,
    documentarySources = emptyList(),
    structuralExtras = extraSources,
    questionRefs = questionRefs,
)

internal fun verifyGeneratedText(
    text: String,
    turnContext: ChatTurnContext? = null,
    documentarySources: List<com.bettertalker.app.data.copilot.RecoveredDocument> = emptyList(),
    structuralExtras: List<String> = emptyList(),
    questionRefs: List<String> = emptyList(),
): VerificationResult {
    val corpus = buildList {
        turnContext?.let { addAll(verificationCorpus(it)) }
        addAll(
            documentarySources
                .filter { it.isDocumentaryEligible() }
                .map { it.text }
        )
    }.filter { it.isNotBlank() }

    // P5 — decisão central ANTES de qualquer transformação: aspas abertas e
    // citações não fundadas são detectadas mesmo quando o corpus é não vazio
    // e o verificador legado exige aspas fechadas.
    val initialGate = ContentEligibilityGate.forFinalAnswer(text, corpus, questionRefs)
    // Verificações complementares permanecem ativas.
    val strict = GroundednessVerifier.verify(text, corpus)
    // Valida também o conteúdo resultante das transformações.
    val afterStrict = ContentEligibilityGate.forFinalAnswer(
        strict.text, corpus, questionRefs,
    )
    // Na geração fora do chat, citações não fundadas são REMOVIDAS com aviso
    // (comportamento certificado); o que não pode seguir é trecho incompleto
    // ou conteúdo resultante vazio. `allowed=false` por remoção comum não
    // equivale a rejeição do texto restante.
    val unfinished = initialGate.findings.any {
        it == FidelityFinding.UNFINISHED_REDACTED
    } || afterStrict.findings.any {
        it == FidelityFinding.UNFINISHED_REDACTED
    }
    val rejected = unfinished || afterStrict.text.isBlank()
    val notice = when {
        unfinished -> initialGate.notice ?: afterStrict.notice
        !initialGate.allowed -> initialGate.notice
        !afterStrict.allowed -> afterStrict.notice
        else -> null
    }
    val removed = (initialGate.removed + strict.removed + afterStrict.removed).distinct()
    val categories = strict.metrics.flaggedByCategory.toMutableMap()
    val unfinishedCount = initialGate.findings.count {
        it == FidelityFinding.UNFINISHED_REDACTED
    }
    if (unfinishedCount > 0) {
        categories[VerificationCategory.QUOTE] =
            (categories[VerificationCategory.QUOTE] ?: 0) + unfinishedCount
    }
    return VerificationResult(
        text = if (rejected) "" else afterStrict.text,
        removed = removed + if (rejected && removed.isEmpty()) {
            listOf("conteúdo rejeitado")
        } else {
            emptyList()
        },
        metrics = VerificationMetrics(
            flaggedChars = removed.sumOf { it.length },
            flaggedByCategory = categories,
        ),
        notice = notice,
    )

}

/**
 * #7e-fix — separa o aviso padrão do verificador do CONTEÚDO gerado: o
 * `REMOVAL_NOTICE` nunca pode virar texto de discurso. Devolve o texto limpo
 * e o aviso (trimado, pronto para o chat/notice) — null quando não há aviso.
 * Puro/testável.
 */
internal fun stripRemovalNotice(text: String): Pair<String, String?> {
    val marker = GroundednessVerifier.REMOVAL_NOTICE
    val markerTrimmed = marker.trim()
    return when {
        text.endsWith(marker) -> text.removeSuffix(marker).trimEnd() to markerTrimmed
        text.endsWith(markerTrimmed) -> text.removeSuffix(markerTrimmed).trimEnd() to markerTrimmed
        text.contains(markerTrimmed) -> text.replace(markerTrimmed, "").trimEnd() to markerTrimmed
        else -> text to null
    }
}
