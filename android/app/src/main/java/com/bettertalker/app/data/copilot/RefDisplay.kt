package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.normalizeText

/**
 * Rótulos de referência para o card/checklist: publicação + UNIDADE.
 *
 * Antes o card mostrava só `DetectedRef.label` (ex.: "Ame as Pessoas" duas
 * vezes) e escondia “Apêndice A” × “Lição 9, ponto 5”. Puro/testável.
 */

/** Unidade legível: capítulo/artigo/página/§. Vazio quando não há unidade. */
fun refUnitLabel(ref: RefDetector.DetectedRef): String {
    val chapter = ref.chapter?.trim().orEmpty()
    val article = ref.article?.trim().orEmpty()
    val paragraph = ref.paragraph
    val end = ref.paragraphEnd
    return buildString {
        if (chapter.isNotBlank()) {
            append(chapter)
            if (paragraph != null) {
                append(", ponto ").append(paragraph)
                if (end != null && end != paragraph) append("-").append(end)
            }
        } else if (article.isNotBlank()) {
            append("“").append(article).append("”")
            if (paragraph != null) {
                append(", §").append(paragraph)
                if (end != null && end != paragraph) append("-").append(end)
            }
        } else {
            if (ref.page != null) append("pág. ").append(ref.page)
            if (paragraph != null) {
                if (isNotEmpty()) append(", ")
                append(if (end != null && end != paragraph) "§§ " else "§")
                    .append(paragraph)
                if (end != null && end != paragraph) append("-").append(end)
            }
        }
    }
}

/** Linha do card: “Ame as Pessoas — lição 9, ponto 5”. */
fun refDisplayLabel(ref: RefDetector.DetectedRef): String {
    val base = ref.label
    // Revistas já trazem página/§ no label; não duplicar.
    if (ref.kind == RefDetector.Kind.MAGAZINE) return base
    val unit = refUnitLabel(ref)
    if (unit.isBlank()) return base
    if (normalizeText(base).contains(normalizeText(unit))) return base
    // O label pode já conter o capítulo/artigo, mas não o ponto.
    val head = unit.substringBefore(',').trim()
    if (head.isNotBlank() && normalizeText(base).contains(normalizeText(head))) {
        val tail = unit.substringAfter(',', "").trim()
        if (tail.isBlank() || normalizeText(base).contains(normalizeText(tail))) return base
    }
    return "$base — $unit"
}

/** Chave de agrupamento: edição para revistas, publicação para livros. */
fun refGroupKey(ref: RefDetector.DetectedRef): String =
    if (ref.kind == RefDetector.Kind.MAGAZINE) ref.editionKey else ref.pubKey

/** Cabeçalho do grupo quando há mais de uma unidade. */
fun refGroupHeader(ref: RefDetector.DetectedRef, count: Int): String {
    val publication = if (ref.kind == RefDetector.Kind.BOOK) {
        com.bettertalker.app.data.util.PubCatalog.titleOf(ref.pubKey) ?: ref.label
    } else {
        ref.label.substringBefore(" • ")
    }
    return "$publication — $count referências"
}
