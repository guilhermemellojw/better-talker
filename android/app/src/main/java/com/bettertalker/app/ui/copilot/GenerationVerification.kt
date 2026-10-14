package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatTurnContext
import com.bettertalker.app.data.llm.GroundednessVerifier
import com.bettertalker.app.data.llm.VerificationResult
import com.bettertalker.app.data.s34.S34Document

/**
 * #7e — corpus textual de um S-34 (objetivo, nota do orador, seções,
 * subseções e refs), no mesmo espírito do [verificationCorpus] do chat.
 * Usado nos caminhos de geração que não têm um [ChatTurnContext].
 * Puro/testável.
 */
internal fun s34CorpusTexts(doc: S34Document): List<String> = buildList {
    doc.objective?.let { add(it) }
    doc.speakerNote?.let { add(it) }
    addAll(doc.headerLines)
    doc.sections.forEach { sec ->
        add(sec.title)
        add(sec.content)
        sec.references.forEach { add(it.rawText) }
        sec.subsections.forEach { sub ->
            add(sub.content)
            sub.references.forEach { add(it.rawText) }
        }
    }
}.filter { it.isNotBlank() }

/**
 * #7e — gate de groundedness para textos GERADOS fora do chat (oratória e
 * "insira tudo"): mesmo verificador do chat, aplicado antes de devolver o
 * texto ao usuário.
 *
 * Corpus = [turnContext] (pack + S-34 + dossiê, quando houver) + [extraSources]
 * (dossiê em contexto, foco, esboço colado…). Corpus vazio → o verificador
 * devolve o texto intacto (não bloqueia). Puro/testável.
 */
internal fun verifyGeneratedText(
    text: String,
    turnContext: ChatTurnContext? = null,
    extraSources: List<String> = emptyList(),
): VerificationResult {
    val corpus = buildList {
        turnContext?.let { addAll(verificationCorpus(it)) }
        addAll(extraSources)
    }.filter { it.isNotBlank() }
    return GroundednessVerifier.verify(text, corpus)
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
