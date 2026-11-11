package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.normalizeText

data class GlobalCitationCheck(
    val ok: Boolean,
    val violations: List<String>,
    val cited: List<Int>,
)

/**
 * Contrato pós-geração do assistente global com fontes da Biblioteca.
 *
 * - `[Fonte N]` precisa existir no intervalo 1..N;
 * - quando há fontes, a resposta precisa citar pelo menos uma;
 * - com fonte disponível, frases de “não consigo acessar” são proibidas.
 *
 * Puro/testável.
 */
object CitationContract {

    private val CITATION_RE = Regex("""\[Fonte\s+(\d+)]""", RegexOption.IGNORE_CASE)
    private val SOURCE_MARKER_RE = Regex("""\s*\[Fonte\s+\d+]""", RegexOption.IGNORE_CASE)
    private val FORBIDDEN = listOf(
        "nao consigo acessar",
        "nao tenho acesso",
        "nao consigo abrir",
        "nao possuo acesso",
        "sem acesso a publicacao",
        "sem acesso ao acervo",
    )

    fun check(
        answer: String,
        sourceCount: Int,
        requireCitation: Boolean = sourceCount > 0,
        sourceAvailable: Boolean = sourceCount > 0,
    ): GlobalCitationCheck {
        val cited = CITATION_RE.findAll(answer)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
        val problems = mutableListOf<String>()
        for (n in cited.distinct()) {
            if (n < 1 || n > sourceCount) problems += "citação [Fonte $n] fora do intervalo"
        }
        if (requireCitation && sourceCount > 0 && cited.none { it in 1..sourceCount }) {
            problems += "resposta sem [Fonte N] válida"
        }
        if (sourceCount == 0 && cited.isNotEmpty()) {
            problems += "resposta cita fonte inexistente"
        }
        if (sourceAvailable) {
            val norm = normalizeText(answer)
            if (FORBIDDEN.any { norm.contains(it) }) {
                problems += "frase de falta de acesso com fonte disponível"
            }
        }
        return GlobalCitationCheck(problems.isEmpty(), problems, cited.distinct())
    }

    /** Remove marcadores de citação antes do TTS. */
    fun stripSourceMarkers(text: String): String =
        SOURCE_MARKER_RE.replace(text, "").trim()
}
