package com.bettertalker.app.data.llm

import com.bettertalker.app.data.util.RefCanonicalizer

/**
 * Verificador de apoio factual pós-geração (universal).
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

// ---------- T2: marcador de criação (〈sugestão〉…〈/sugestão〉) ----------

const val SUGGESTION_OPEN = "〈sugestão〉"
const val SUGGESTION_CLOSE = "〈/sugestão〉"

/** T2 — intervalos do CONTEÚDO dentro de 〈sugestão〉…〈/sugestão〉 (sem as tags). */
fun suggestionSpans(text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var i = 0
    while (true) {
        val open = text.indexOf(SUGGESTION_OPEN, i)
        if (open < 0) break
        val close = text.indexOf(SUGGESTION_CLOSE, open + SUGGESTION_OPEN.length)
        if (close < 0) break
        out += (open + SUGGESTION_OPEN.length) until close
        i = close + SUGGESTION_CLOSE.length
    }
    return out
}

/** T2 — posição dentro de algum trecho marcado como criação? Puro/testável. */
fun inSuggestion(pos: Int, spans: List<IntRange>): Boolean = spans.any { pos in it }

object GroundednessVerifier {

    /**
     * Citações curtas são ruído (ex.: "sim"); só valem a partir daqui.
     * #15c: 12 → 20 — citações curtas legítimas viravam falso-positivo de
     * remoção (o verificador é conservador por desenho).
     */
    const val MIN_QUOTE_CHARS = 20

    const val REMOVAL_NOTICE =
        "\n\n(Observação: removi uma frase que não tinha apoio nas fontes.)"

    private val QUOTE = Regex(
        "\"([^\"]{%d,})\"|“([^”]{%d,})”".format(MIN_QUOTE_CHARS, MIN_QUOTE_CHARS),
        RegexOption.DOT_MATCHES_ALL,
    )
    private val REF = Regex("(?i)\\b(p[áa]gina|par[áa]grafo|cap[íi]tulo)\\s*(n[.ºo°]*\\s*)?(\\d+)")
    /** T2 — versículo citado ("Gên 1:26"): nunca isento, nem em criação. */
    private val VERSE = Regex("""\b((?:[1-3]\s+)?[A-Za-zÀ-ÿ]+)\s+(\d{1,3})\s*:\s*(\d{1,3})\b""")
    /** T2 — número com 2+ dígitos: nunca isento, nem em criação. */
    private val NUMBER = Regex("""\b(\d{1,4}(?:[.,]\d{1,2})?)\b""")

    /**
     * T1 (modo colar e conversar) — modo aviso: detecta os mesmos trechos,
     * mas NÃO remove nada (texto intacto + lista de removidos para o aviso
     * "⚠️ Revise"). O modo padrão (remoção) continua inalterado.
     */
    const val WARN_ONLY_NOTICE = "⚠️ Revise: há trecho sem apoio no esboço colado."

    fun verify(text: String, sources: List<String>, warnOnly: Boolean = false): VerificationResult {
        if (text.isBlank() || sources.isEmpty()) return VerificationResult(text, emptyList())
        val corpus = sources.map(::normalize).filter { it.isNotBlank() }
        if (corpus.isEmpty()) return VerificationResult(text, emptyList())
        // #16-fix-f: formas canônicas do corpus (livros abreviados + ranges
        // expandidos + páginas) — a comparação de refs usa estas.
        val canonCorpus = corpus.flatMap { RefCanonicalizer.canonicalize(it) }
        val spans = suggestionSpans(text)

        val ranges = mutableListOf<IntRange>()
        QUOTE.findAll(text).forEach { m ->
            // T2: prosa criativa marcada é isenta; criação sem marcador continua gate normal.
            if (inSuggestion(m.range.first, spans)) return@forEach
            val quote = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (quote.length >= MIN_QUOTE_CHARS && corpus.none { it.contains(normalize(quote)) }) {
                ranges += sentenceAround(text, m.range)
            }
        }
        REF.findAll(text).forEach { m ->
            if (inSuggestion(m.range.first, spans)) return@forEach
            // #16-fix-f: compara o NÚMERO (o corpus pode trazer "26-28" onde
            // o modelo escreveu "páginas 26 a 28").
            val number = m.groupValues[3]
            if (canonCorpus.none { it.contains(number) }) {
                ranges += sentenceAround(text, m.range)
            }
        }
        // T2 — dentro de 〈sugestão〉 a prosa é isenta, mas versículo e número NÃO:
        // invenção de dado nunca passa por ser "criação".
        for (span in spans) {
            val slice = text.substring(span.first, span.last + 1)
            val verseRanges = mutableListOf<IntRange>()
            VERSE.findAll(slice).forEach { m ->
                val g = (span.first + m.range.first)..(span.first + m.range.last)
                verseRanges += g
                // #16-fix-f: canonicaliza o livro ("1 Coríntios" → "1co") e
                // aceita versículo individual dentro de range autorizado.
                if (canonCorpus.none { it.contains(RefCanonicalizer.canonicalBook(m.value)) }) {
                    ranges += sentenceAround(text, g)
                }
            }
            NUMBER.findAll(slice).forEach { m ->
                val n = m.groupValues[1]
                if (n.length <= 1) return@forEach
                // #16-fix-c: "S-34" — o dígito faz parte do código, não é dado.
                if (m.range.first >= 2 &&
                    slice.regionMatches(m.range.first - 2, "S-", 0, 2, ignoreCase = true)
                ) return@forEach
                val g = (span.first + m.range.first)..(span.first + m.range.last)
                if (verseRanges.any { g.first in it }) return@forEach
                if (canonCorpus.none { it.contains(n) }) {
                    ranges += sentenceAround(text, g)
                }
            }
        }
        if (ranges.isEmpty()) return VerificationResult(text, emptyList())

        val merged = merge(ranges)
        val removed = merged
            .map { text.substring(it.first, it.last + 1).trim() }
            .filter { it.isNotBlank() }
        // T1 (modo aviso): o chamador exibe o aviso; o texto segue intacto.
        if (warnOnly) return VerificationResult(text, removed)
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

    /**
     * Normaliza para comparação: caixa, espaços (inclusive Unicode/NBSP),
     * acentos, marcas de markdown (`*`, `#`, `>`) e tipografia (travessões →
     * `-`, reticências → `...`, aspas curvas → retas) — o modelo pode
     * enfatizar/titular dentro da citação, e sem isso `**"citação"**` ou
     * `—`/`…` divergentes não casavam com o corpus (falso-positivo).
     * #15c: a MESMA função normaliza citação e corpus (comparação simétrica).
     */
    internal fun normalize(s: String): String = s
        .lowercase()
        .replace(Regex("[*#>]"), "")
        .replace(Regex("[\\s\\u00A0\\u2007\\u2009\\u202F\\uFEFF]+"), " ")
        .replace(Regex("\\s*[\\u2010-\\u2015\\u2212-]\\s*"), "-")
        .replace("…", "...")
        .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a')
        .replace('é', 'e').replace('ê', 'e')
        .replace('í', 'i')
        .replace('ó', 'o').replace('ô', 'o').replace('õ', 'o')
        .replace('ú', 'u')
        .replace('ç', 'c')
        .replace('“', '"').replace('”', '"')
        .replace('‘', '\'').replace('’', '\'')
        .trim()
}
