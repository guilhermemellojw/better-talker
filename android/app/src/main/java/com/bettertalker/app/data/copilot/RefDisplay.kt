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
            // Livro cita página impressa ("p. 29"); revista mantém "pág.".
            if (ref.page != null) {
                append(if (ref.kind == RefDetector.Kind.BOOK) "p. " else "pág. ")
                    .append(ref.page)
            }
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
    val unit = refUnitLabel(ref)
    if (unit.isBlank()) return base
    if (normalizeText(base).contains(normalizeText(unit))) return base

    // 6.0a: o label do detector pode já trazer "p. 29" (jr 29) ou
    // "p. 27 § 22". Remove da unidade só o que o label já mostra, para não
    // duplicar página/§ na linha do SourcesSheet.
    var remaining = unit
    val page = ref.page
    if (page != null && Regex(
            """(?i)p(?:ág|ag)?\.?\s*$page(?:\s*[-–]\s*\d+)?"""
        ).containsMatchIn(base)
    ) {
        remaining = remaining.replaceFirst(
            Regex("""(?i)p(?:ág|ag)?\.?\s*\d+(?:\s*[-–]\s*\d+)?,?\s*"""),
            "",
        ).trim()
    }
    val par = ref.paragraph
    if (par != null && Regex("""§{1,2}\s*$par(?:\s*[-–]\s*\d+)?""")
            .containsMatchIn(base)
    ) {
        remaining = remaining.replaceFirst(
            Regex("""§{1,2}\s*\d+(?:\s*[-–]\s*\d+)?,?\s*"""),
            "",
        ).trim()
    }
    if (remaining.isBlank()) return base

    // O label pode já conter o capítulo/artigo, mas não o ponto.
    val head = remaining.substringBefore(',').trim()
    if (head.isNotBlank() && normalizeText(base).contains(normalizeText(head))) {
        val tail = remaining.substringAfter(',', "").trim()
        if (tail.isBlank() || normalizeText(base).contains(normalizeText(tail))) return base
    }
    return if (remaining.startsWith("§")) "$base $remaining" else "$base — $remaining"
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
