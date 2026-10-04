package com.bettertalker.app.data.llm

/**
 * F2 (Gemma local) — verificador de apoio factual pós-geração.
 *
 * Conservador por desenho: só remove frases com evidência CLARA de falta de
 * apoio — citações entre aspas (>= [MIN_QUOTE_CHARS]) que não aparecem nas
 * fontes de conteúdo, e referências explícitas (página/parágrafo/capítulo N)
 * que não constam das fontes. Nunca reescreve o restante: preserva o texto
 * original e apenas retira as frases problemáticas + aviso discreto.
 *
 * Puro/testável: sem Android, sem IO.
 */
data class VerificationResult(
    val text: String,
    val removed: List<String>,
) {
    val hasRemovals: Boolean get() = removed.isNotEmpty()
}

object GroundednessVerifier {

    /** Citações curtas são ruído (ex.: "sim"); só valem a partir daqui. */
    const val MIN_QUOTE_CHARS = 12

    const val REMOVAL_NOTICE =
        "\n\n(Observação: removi uma frase que não tinha apoio nas fontes.)"

    private val QUOTE = Regex(
        "\"([^\"]{%d,})\"|“([^”]{%d,})”".format(MIN_QUOTE_CHARS, MIN_QUOTE_CHARS),
        RegexOption.DOT_MATCHES_ALL,
    )
    private val REF = Regex("(?i)\\b(p[áa]gina|par[áa]grafo|cap[íi]tulo)\\s*(n[.ºo°]*\\s*)?(\\d+)")

    fun verify(text: String, sources: List<String>): VerificationResult {
        if (text.isBlank() || sources.isEmpty()) return VerificationResult(text, emptyList())
        val corpus = sources.map(::normalize).filter { it.isNotBlank() }
        if (corpus.isEmpty()) return VerificationResult(text, emptyList())

        val ranges = mutableListOf<IntRange>()
        QUOTE.findAll(text).forEach { m ->
            val quote = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (quote.length >= MIN_QUOTE_CHARS && corpus.none { it.contains(normalize(quote)) }) {
                ranges += sentenceAround(text, m.range)
            }
        }
        REF.findAll(text).forEach { m ->
            if (corpus.none { it.contains(normalize(m.value)) }) {
                ranges += sentenceAround(text, m.range)
            }
        }
        if (ranges.isEmpty()) return VerificationResult(text, emptyList())

        val merged = merge(ranges)
        val removed = merged
            .map { text.substring(it.first, it.last + 1).trim() }
            .filter { it.isNotBlank() }
        val kept = StringBuilder()
        var cursor = 0
        for (r in merged) {
            if (r.first > cursor) kept.append(text.substring(cursor, r.first))
            cursor = r.last + 1
        }
        if (cursor < text.length) kept.append(text.substring(cursor))
        val cleaned = kept.toString().trim().replace(Regex(" {2,}"), " ")
        return VerificationResult(cleaned + REMOVAL_NOTICE, removed)
    }

    /**
     * Expande o trecho problemático até as bordas da(s) frase(s) que o contém.
     * Se o próprio trecho já contém um terminador (ex.: citação que termina em
     * ponto dentro das aspas), não engole a frase seguinte.
     */
    private fun sentenceAround(text: String, range: IntRange): IntRange {
        var start = range.first
        while (start > 0 && text[start - 1] !in ".!?…\n") start--
        var end = range.last
        val spanHasTerminator = text.substring(range.first, range.last + 1)
            .any { it in ".!?…" }
        if (!spanHasTerminator) {
            while (end < text.lastIndex && text[end + 1] !in ".!?…\n") end++
            if (end < text.lastIndex && text[end + 1] in ".!?…") end++
        }
        return start..end
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                out[out.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                out += r
            }
        }
        return out
    }

    /** Normaliza para comparação: caixa, espaços e acentos. */
    internal fun normalize(s: String): String = s
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a')
        .replace('é', 'e').replace('ê', 'e')
        .replace('í', 'i')
        .replace('ó', 'o').replace('ô', 'o').replace('õ', 'o')
        .replace('ú', 'u')
        .replace('ç', 'c')
        .replace('“', '"').replace('”', '"')
        .trim()
}
