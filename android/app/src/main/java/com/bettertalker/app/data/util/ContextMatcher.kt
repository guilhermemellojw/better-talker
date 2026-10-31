package com.bettertalker.app.data.util

/**
 * C2.6 — fallback por contexto para edições sem página/§ indexados
 * (PDF/EPUB reflowable). A frase do esboço vira a consulta; o trecho só é
 * aceito acima de um piso de termos, nunca por uma palavra solta.
 *
 * Puro/testável.
 */
data class ContextMatch(
    val index: Int,
    val matchedTerms: Int,
    val totalTerms: Int,
) {
    val ratio: Double get() = if (totalTerms == 0) 0.0 else matchedTerms.toDouble() / totalTerms
}

/** Termos úteis da frase: normalizados, >= 4 chars, sem stopwords. */
fun contextTerms(context: String): List<String> =
    normalizeText(context)
        .split(Regex("""\s+"""))
        .map { it.trim() }
        .filter { it.length >= 4 && it !in STOPWORDS_PT }
        .distinct()

/** Quantos termos da frase aparecem no trecho. */
fun contextOverlap(terms: List<String>, passageText: String): Int {
    if (terms.isEmpty()) return 0
    val normalized = normalizeText(passageText)
    return terms.count { normalized.contains(it) }
}

/**
 * Melhor trecho para a frase do esboço. Exige:
 * - pelo menos [minMatches] termos distintos;
 * - pelo menos [minRatio] dos termos da frase.
 *
 * Retorna null quando o contexto é fraco (não devolve trecho aleatório).
 */
fun bestContextMatch(
    passages: List<String>,
    context: String,
    minMatches: Int = 3,
    minRatio: Double = 0.25,
): ContextMatch? {
    val terms = contextTerms(context)
    if (terms.size < minMatches) return null
    var best: ContextMatch? = null
    passages.forEachIndexed { index, text ->
        val matched = contextOverlap(terms, text)
        if (matched < minMatches) return@forEachIndexed
        val candidate = ContextMatch(index, matched, terms.size)
        if (candidate.ratio < minRatio) return@forEachIndexed
        if (best == null || candidate.matchedTerms > best!!.matchedTerms) {
            best = candidate
        }
    }
    return best
}

/**
 * C2.8 — como [bestContextMatch], mas em janelas de [window] trechos
 * consecutivos. Uma ideia pode estar dividida em várias passagens (título,
 * frase, referência); a janela captura o conjunto sem exigir que todos os
 * termos caiam numa única linha.
 */
fun bestContextWindow(
    passages: List<String>,
    context: String,
    window: Int = 3,
    minMatches: Int = 3,
    minRatio: Double = 0.25,
): ContextMatch? {
    if (window <= 0 || passages.isEmpty()) return null
    val terms = contextTerms(context)
    if (terms.size < minMatches) return null
    var best: ContextMatch? = null
    for (start in passages.indices) {
        val end = (start + window).coerceAtMost(passages.size)
        val text = passages.subList(start, end).joinToString(" ")
        val matched = contextOverlap(terms, text)
        if (matched < minMatches) continue
        val candidate = ContextMatch(start, matched, terms.size)
        if (candidate.ratio < minRatio) continue
        if (best == null || candidate.matchedTerms > best!!.matchedTerms) {
            best = candidate
        }
    }
    return best
}
