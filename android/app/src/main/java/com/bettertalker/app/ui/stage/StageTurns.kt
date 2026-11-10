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
 *
 * v5.3: o Palco e a Tribuna usam [parseStageScript], que preserva também os
 * parágrafos sem papel (direções, narrativa, leitura) como narração. O Treino
 * continua usando [parseStageTurns], que devolve só os turnos falados.
 */

/** Um turno do diálogo (papel + fala) ou um bloco de narração. */
data class StageTurn(
    val speaker: String,
    val text: String,
    val kind: StageTurnKind = StageTurnKind.SPEECH,
) {
    val isNarration: Boolean get() = kind == StageTurnKind.NARRATION
}

enum class StageTurnKind { SPEECH, NARRATION }

private val B_RE = Regex("""^\s*<b>(.*?)</b>\s*:?\s*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SCRIPT_BLOCK_RE = Regex(
    """<(p|li|h[1-6]|blockquote)\b[^>]*>(.*?)</\1>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

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
 * v5.3: reusa o parser do roteiro e descarta a narração (Treino/TTS).
 */
fun parseStageTurns(html: String): List<StageTurn> =
    parseStageScript(html).filter { it.kind == StageTurnKind.SPEECH }

/**
 * v5.3: roteiro completo de uma demonstração, na ordem do HTML.
 *
 * Turnos com papel continuam [StageTurnKind.SPEECH]; qualquer bloco de texto
 * sem papel reconhecido vira [StageTurnKind.NARRATION]. Assim direções,
 * cenário e leitura não somem no Palco/Tribuna. Sem tags de bloco, o HTML
 * inteiro é uma narração (fallback de texto corrido).
 */
fun parseStageScript(html: String): List<StageTurn> {
    if (html.isBlank()) return emptyList()
    val out = mutableListOf<StageTurn>()
    for (raw in scriptBlocks(html)) {
        val block = raw.trim()
        if (block.isEmpty()) continue
        val m = B_RE.find(block) ?: PLAIN_RE.find(block)
        if (m != null) {
            val speaker = cleanStageText(m.groupValues[1]).trimEnd(':', '：').trim()
            val text = cleanStageText(m.groupValues[2])
            if (speaker.isNotEmpty() && text.isNotEmpty()) {
                out += StageTurn(speaker, text, StageTurnKind.SPEECH)
                continue
            }
        }
        val narration = cleanStageText(block)
        if (narration.isNotEmpty()) {
            out += StageTurn("", narration, StageTurnKind.NARRATION)
        }
    }
    return out
}

/**
 * Blocos de texto em ordem de documento. Aceita `<p>`, `<li>`, títulos e
 * `<blockquote>`; listas aninhadas são abertas em blocos internos. Texto solto
 * entre blocos também é preservado (vira narração).
 */
private fun scriptBlocks(html: String): List<String> {
    val matches = SCRIPT_BLOCK_RE.findAll(html).toList()
    if (matches.isEmpty()) return listOf(html)
    val out = mutableListOf<String>()
    var last = 0
    matches.forEach { match ->
        if (match.range.first > last) out += html.substring(last, match.range.first)
        val tag = match.groupValues[1].lowercase()
        val inner = match.groupValues[2]
        if (tag != "p" && SCRIPT_BLOCK_RE.containsMatchIn(inner)) {
            out += scriptBlocks(inner)
        } else {
            out += inner
        }
        last = match.range.last + 1
    }
    if (last < html.length) out += html.substring(last)
    return out
}

/** Texto de fala para o TTS: só as falas (sem os rótulos/narração). Puro/testável. */
fun stageSpeechText(turns: List<StageTurn>, fallback: String): String {
    val speech = turns
        .filter { it.kind == StageTurnKind.SPEECH }
        .joinToString(" ") { it.text }
    return speech.ifBlank { fallback }
}

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
