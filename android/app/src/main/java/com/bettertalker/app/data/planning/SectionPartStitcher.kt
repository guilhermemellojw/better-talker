package com.bettertalker.app.data.planning

import com.bettertalker.app.data.util.htmlToPlainText

/**
 * M3/45 min — stitch das partes internas de uma seção monolítica.
 *
 * - junta os parágrafos `<p>` na ordem;
 * - remove parágrafo consecutivo duplicado (o modelo repete a transição);
 * - opcionalmente corta na última frase completa e fecha `<p>`/`<strong>`/
 *   `<em>` abertos.
 *
 * Puro/testável.
 */
internal fun stitchSectionParts(
    parts: List<String>,
    cutLast: Boolean = false,
): String {
    val paragraphs = parts.flatMap { splitParagraphs(it) }
    val out = mutableListOf<String>()
    for (p in paragraphs) {
        val normalized = htmlToPlainText(p).trim().lowercase()
        if (normalized.isEmpty()) continue
        if (out.isNotEmpty() &&
            htmlToPlainText(out.last()).trim().lowercase() == normalized
        ) {
            continue
        }
        out += p
    }
    var html = out.joinToString("")
    if (cutLast) html = cutAtLastCompleteSentence(html)
    return html
}

/**
 * Split linear (sem regex `.*?`): um parágrafo de 3.000 palavras em uma
 * linha estourava a pilha do regex do Java. Aceita `<p>` sem fechamento
 * (parcial cortado).
 */
private fun splitParagraphs(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (!trimmed.contains("<p", ignoreCase = true)) {
        return listOf("<p>$trimmed</p>")
    }
    val out = mutableListOf<String>()
    var pos = 0
    while (pos < trimmed.length) {
        val open = trimmed.indexOf("<p", pos, ignoreCase = true)
        if (open < 0) {
            val rest = trimmed.substring(pos).trim()
            if (rest.isNotEmpty()) {
                out += if (rest.startsWith("<")) rest else "<p>$rest</p>"
            }
            break
        }
        if (open > pos) {
            val before = trimmed.substring(pos, open).trim()
            if (before.isNotEmpty()) out += "<p>$before</p>"
        }
        val close = trimmed.indexOf("</p>", open, ignoreCase = true)
        if (close < 0) {
            out += trimmed.substring(open)
            break
        }
        out += trimmed.substring(open, close + "</p>".length)
        pos = close + "</p>".length
    }
    return out
}

/** Corta no último `.`/`!`/`?`/`…` e fecha as tags abertas. */
internal fun cutAtLastCompleteSentence(html: String): String {
    val last = html.indexOfLast { it in ".!?…" }
    if (last < 0) return html
    var cut = html.substring(0, last + 1)
    // Fecha inline antes do parágrafo, senão a ordem vira </p></strong>.
    if (markerCount(cut, "<strong>") > markerCount(cut, "</strong>")) cut += "</strong>"
    if (markerCount(cut, "<em>") > markerCount(cut, "</em>")) cut += "</em>"
    val opens = markerCount(cut, "<p")
    val closes = markerCount(cut, "</p>")
    if (opens > closes) cut += "</p>"
    return cut
}

