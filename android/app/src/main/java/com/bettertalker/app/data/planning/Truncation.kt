package com.bettertalker.app.data.planning

/**
 * Heurística de corte de draft: `finishReason == "length"` OU texto sem
 * pontuação terminal.
 *
 * O `qwen/qwen3.8-27b` retorna "stop" mesmo cortando no meio da frase
 * (com folga no orçamento) — por isso a segunda condição. Pontuação
 * aceita no fim: . ! ? … " ) : ; » ” ’
 *
 * O texto do draft é HTML (`<p>` por parágrafo) — as tags são
 * ignoradas na verificação (mesma regex do `stripHtml`).
 *
 * Pura, testável.
 */
internal fun looksTruncated(text: String, finishReason: String?): Boolean {
    if (finishReason == "length") return true
    val content = text.replace(Regex("<[^>]+>"), "").trim()
    if (content.isEmpty()) return true
    return content.last() !in ".!?…\")»”’:;"
}

/**
 * Tamanho mínimo para truncar: respostas curtas ("Sim", "Ok") legitimamente
 * não terminam em pontuação — mexer nelas seria falso positivo.
 */
internal const val MIN_LENGTH_FOR_TRUNCATION = 100

/**
 * #25-fix-b — "corte curador": fecha a sintaxe pendente do trecho cortado
 * (`**` desbalanceado, `〈sugestão〉`/`〈decisão〉` abertos, `<texto-do-card>`
 * sem fechamento) e sinaliza a interrupção com `[...]`. Sem esse fechamento,
 * o render inline vazaria o negrito/marcador para o resto da conversa.
 *
 * Só atua se o texto passa de [MIN_LENGTH_FOR_TRUNCATION] E
 * [looksTruncated] acusa corte (que já cobre `finishReason == "length"`).
 * Sem pontuação terminal para cortar, devolve o original. Pura, testável.
 */
internal fun truncateIfCut(text: String, finishReason: String?): String {
    if (text.length <= MIN_LENGTH_FOR_TRUNCATION) return text
    if (!looksTruncated(text, finishReason)) return text
    val last = text.indexOfLast { it in ".!?…" }
    if (last <= 0) return text
    var cut = text.substring(0, last + 1).trimEnd()
    // `**` ímpar → fecha o negrito que ficou aberto.
    if (markerCount(cut, "**") % 2 != 0) cut += "**"
    // Anotações abertas → fecha (o marcador de fechamento não é contado como
    // abertura: "〈/sugestão〉" não contém "〈sugestão〉").
    if (markerCount(cut, "〈sugestão〉") > markerCount(cut, "〈/sugestão〉")) {
        cut += "〈/sugestão〉"
    }
    if (markerCount(cut, "〈decisão〉") > markerCount(cut, "〈/decisão〉")) {
        cut += "〈/decisão〉"
    }
    if (cut.contains("<texto-do-card>") && !cut.contains("</texto-do-card>")) {
        cut += "</texto-do-card>"
    }
    return "$cut [...]"
}

/** #25-fix-b: ocorrências de [marker] em [text] (puro/testável). */
internal fun markerCount(text: String, marker: String): Int =
    if (marker.isEmpty()) 0 else text.split(marker).size - 1
