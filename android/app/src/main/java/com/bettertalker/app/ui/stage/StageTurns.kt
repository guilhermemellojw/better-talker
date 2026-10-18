package com.bettertalker.app.ui.stage

import com.bettertalker.app.data.util.decodeHtmlEntities

/**
 * Modo Teleprompter (demonstrações): turnos do diálogo no Ensaio.
 *
 * O contentHtml das demonstrações coladas/geradas usa
 * `<p><b>Papel:</b> fala</p>` — este parser separa os turnos para a tela
 * destacar os papéis e para o TTS ler só as falas (sem o rótulo). Também
 * aceita o rótulo SEM negrito (`<p>Papel&colon; fala</p>`), comum em diálogo
 * digitado ou inserido pelo Copilot ("Usar no card").
 * Sem nenhum turno reconhecido devolve lista vazia (a tela cai no texto
 * corrido). Puro/testável.
 */

/** Um turno do diálogo (papel + fala). */
data class StageTurn(val speaker: String, val text: String)

private val P_RE = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val B_RE = Regex("""^\s*<b>(.*?)</b>\s*:?\s*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

// Rótulo sem negrito: papel de 1–3 palavras (sem tags/entidades) + ":" ou
// "&colon;". O quantificador mínimo para no 1º separador, então uma fala com
// dois-pontos no meio ("Publicador: ... uma coisa: ...") não vira papel.
private val PLAIN_RE = Regex(
    """^\s*([^:<>&\s]+(?:\s+[^:<>&\s]+){0,2})\s*(?::|&colon;)\s*(.*)$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** Texto puro de um trecho HTML (tags fora, entidades decodificadas, <br> → \n). Puro. */
internal fun cleanStageText(html: String): String =
    decodeHtmlEntities(
        html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
    )
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()

/**
 * Parseia `<p><b>Papel:</b> fala</p>` (um ou mais parágrafos) em turnos.
 * Aceita spans/tags residuais dentro da fala; exige papel E fala não vazios.
 */
fun parseStageTurns(html: String): List<StageTurn> {
    if (html.isBlank()) return emptyList()
    val blocks = P_RE.findAll(html).map { it.groupValues[1] }.toList()
        .ifEmpty { listOf(html) }
    val out = mutableListOf<StageTurn>()
    for (block in blocks) {
        val m = B_RE.find(block) ?: PLAIN_RE.find(block) ?: continue
        val speaker = cleanStageText(m.groupValues[1]).trimEnd(':', '：').trim()
        val text = cleanStageText(m.groupValues[2])
        if (speaker.isNotEmpty() && text.isNotEmpty()) out += StageTurn(speaker, text)
    }
    return out
}

/** Texto de fala para o TTS: só as falas (sem os rótulos). Puro/testável. */
fun stageSpeechText(turns: List<StageTurn>, fallback: String): String =
    if (turns.isEmpty()) fallback else turns.joinToString(" ") { it.text }

/**
 * Próximo papel do "Modo Dupla": null → 1º papel → 2º → … → null (cíclico).
 * Puro/testável.
 */
fun nextDuoRole(current: String?, speakers: List<String>): String? {
    if (speakers.isEmpty()) return null
    val cycle = listOf<String?>(null) + speakers
    val idx = cycle.indexOf(current)
    return cycle[(idx + 1) % cycle.size]
}
