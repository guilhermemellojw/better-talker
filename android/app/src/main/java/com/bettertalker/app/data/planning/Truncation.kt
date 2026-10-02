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
