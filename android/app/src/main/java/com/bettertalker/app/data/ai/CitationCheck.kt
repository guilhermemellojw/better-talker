package com.bettertalker.app.data.ai

import com.bettertalker.app.data.llm.inSuggestion
import com.bettertalker.app.data.llm.suggestionSpans
import com.bettertalker.app.data.util.normalizeText

/**
 * Pós-checagem anti-alucinação: valida o texto gerado contra os trechos
 * que alimentaram o prompt. Puro/testável.
 */
data class CitationCheck(val ok: Boolean, val violations: List<String>)

/**
 * Regras:
 * - todo [n] citado precisa existir nos trechos (1-based);
 * - menção a versículo (ex: "Gên 1:26") precisa aparecer em algum trecho;
 * - aspas longas (>= 8 palavras) precisam estar contidas em algum trecho.
 */
fun checkCitations(generated: String, passages: List<String>): CitationCheck {
    val problems = mutableListOf<String>()
    val cited = Regex("""\[(\d+)\]""").findAll(generated)
        .map { it.groupValues[1].toIntOrNull() ?: -1 }.toList()
    for (n in cited) {
        if (n < 1 || n > passages.size) problems += "citação [$n] sem trecho"
    }
    val normPassages = passages.map { normalizeText(it) }
    // versículos citados precisam constar nos trechos (compara normalizado)
    val verseRe = Regex("""\b([a-zà-ÿ]{2,4})\.?\s*(\d{1,3})\s*:\s*(\d{1,3})\b""")
    val stop = setOf(
        "as", "ao", "aos", "da", "das", "de", "do", "dos", "em", "na", "nas",
        "no", "nos", "por", "para", "com", "sem", "sob", "sobre", "entre",
        "ate", "o", "a", "os", "que", "se", "um", "uma", "e", "ou"
    )
    for (m in verseRe.findAll(generated.lowercase())) {
        val book = normalizeText(m.groupValues[1])
        if (book in stop) continue // "às 19:30" não é versículo
        val v = "$book ${m.groupValues[2]} ${m.groupValues[3]}"
        if (normPassages.none { it.contains(v) }) problems += "versículo fora dos trechos: ${m.value}"
    }
    // aspas longas precisam estar nos trechos (tolera pontuação; "" e “”)
    // T2: em trecho marcado como criação (〈sugestão〉), a prosa é isenta;
    // versículo e citação [n] continuam checados (linhas acima) — regra de ouro.
    val spans = suggestionSpans(generated)
    val quoteRe = Regex("[\"“]([^\"”]{20,})[\"”]")
    for (m in quoteRe.findAll(generated)) {
        if (inSuggestion(m.range.first, spans)) continue
        val q = normalizeText(m.groupValues[1])
        val words = q.split(" ").filter { it.length > 2 }
        if (words.size >= 8 && normPassages.none { p -> words.count { p.contains(it) } >= 6 }) {
            problems += "aspas sem fonte: “${m.groupValues[1].take(60)}…”"
        }
    }
    return CitationCheck(problems.isEmpty(), problems)
}

/**
 * Detecta frase repetida verbatim (loop típico de modelo pequeno com
 * decodificação gulosa). Puro/testável — chamador regenera ou para.
 */
fun hasRepetition(text: String): Boolean {
    val sents = text.split(Regex("[.!?\\n]+"))
        .map { normalizeText(it) }
        .filter { it.length > 10 }
    return sents.size != sents.toSet().size
}
