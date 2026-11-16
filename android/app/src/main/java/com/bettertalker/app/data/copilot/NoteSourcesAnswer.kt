package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.citationDetailLine
import com.bettertalker.app.data.util.citationStatusLabel
import com.bettertalker.app.data.util.isMissing
import com.bettertalker.app.data.util.isPending
import com.bettertalker.app.data.util.normalizeText

private val SOURCES_WORD = Regex("\\bfontes\\b")
private val NOTE_SCOPE = Regex("\\b(nota|discurso|esboco)\\b")
private val INVENTORY_ASK = Regex(
    "\\b(faltam|faltando|pendentes|status|inventario|quais|resolver|revisar)\\b"
)

/**
 * “Quais fontes desta nota ainda faltam?” / “status das fontes da nota”:
 * pergunta de inventário da NOTA, não do ponto em foco. Puro/testável.
 */
internal fun isNoteSourcesInventoryQuestion(text: String): Boolean {
    val t = normalizeText(text)
    return SOURCES_WORD.containsMatchIn(t) &&
        NOTE_SCOPE.containsMatchIn(t) &&
        INVENTORY_ASK.containsMatchIn(t)
}

/**
 * Resposta determinística para o inventário da nota: usa os status/ações
 * reais de [NoteSources], nunca o foco do ponto nem o LLM. Puro/testável.
 */
internal fun buildNoteSourcesInventoryAnswer(sources: NoteSources): String {
    val actionable = sources.sources
        .filter { it.status.isMissing() || it.status.isPending() || it.citation.needsAction }
        .distinctBy { "${it.kind}|${it.ref}|${it.editionKey.orEmpty()}" }
    val head = "A nota tem ${sources.total} referências: ${sources.resolved} disponíveis"
    if (actionable.isEmpty()) return "$head. Não há fontes faltando."

    val lines = actionable.map { source ->
        "- ${source.ref} — ${citationStatusLabel(source)} — ${citationDetailLine(source)}"
    }
    return buildString {
        append(head)
        append("; ")
        append(actionable.size)
        append(" ainda precisam de atenção.\n\n")
        append(lines.joinToString("\n"))
        append("\n\nPara resolver, abra Fontes e apoio ou a Biblioteca.")
    }
}
