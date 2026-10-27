package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.data.util.isMissing
import com.bettertalker.app.data.util.isPending

/**
 * Inventário compacto de referências da nota para o prompt do Copilot.
 *
 * É METADADO, não texto-fonte: o texto continua vindo do RAG/dossiê. A lista
 * vem do mesmo coletor do checklist ("Fontes X/Y") — [NoteSources] — para que
 * o modelo nunca diga "não encontrei" uma ref que a UI mostra.
 *
 * Regras:
 * - refs não resolvidas SEMPRE aparecem (nunca somem em silêncio);
 * - `be`/`th` entram como orientação de oratória, nunca como conteúdo;
 * - status TEXT_NOT_FOUND diz "edição no acervo; trecho não encontrado"
 *   (não "publicação ausente");
 * - teto de [MAX_REFS_INVENTORY_CHARS] com prioridade para foco, não
 *   resolvidas e Bíblia; o excedente é marcado, nunca escondido.
 *
 * Puro/testável: sem Android, sem IO.
 */

const val MAX_REFS_INVENTORY_CHARS = 4_000

private const val HEADER = "--- REFERÊNCIAS DA NOTA (inventário completo) ---"
private const val FOOTER = "--- FIM DAS REFERÊNCIAS DA NOTA ---"

data class NoteRefsInventory(
    val text: String,
    val included: Int,
    val omitted: Int,
) {
    val isEmpty: Boolean get() = text.isBlank()
}

private enum class InventoryGroup(val title: String) {
    CONTENT("Publicações citadas:"),
    TRAINING("Orientação de oratória (não é fonte de conteúdo):"),
    BIBLE("Bíblia:"),
}

private data class InventoryEntry(
    val group: InventoryGroup,
    val source: NoteSource,
    val priority: Int,
    val line: String,
)

/**
 * Formata o inventário. Retorna string vazia quando a nota não cita nada.
 */
fun buildNoteRefsInventory(
    sources: NoteSources,
    focusKeys: Set<String> = emptySet(),
    maxChars: Int = MAX_REFS_INVENTORY_CHARS,
): String = buildNoteRefsInventoryDetailed(sources, focusKeys, maxChars).text

/**
 * Versão com métricas (para telemetria): quantas refs entraram/ficaram fora.
 */
fun buildNoteRefsInventoryDetailed(
    sources: NoteSources,
    focusKeys: Set<String> = emptySet(),
    maxChars: Int = MAX_REFS_INVENTORY_CHARS,
): NoteRefsInventory {
    val all = sources.sources
        .distinctBy { "${it.kind}|${it.ref}|${it.editionKey.orEmpty()}" }
    if (all.isEmpty()) return NoteRefsInventory("", 0, 0)

    val entries = all.map { source ->
        val group = when {
            source.kind == SourceKind.BIBLE -> InventoryGroup.BIBLE
            source.isTraining() -> InventoryGroup.TRAINING
            else -> InventoryGroup.CONTENT
        }
        InventoryEntry(
            group = group,
            source = source,
            priority = priorityOf(source, focusKeys),
            line = "- ${source.ref} — ${statusLabel(source)}",
        )
    }

    // Orçamento: header + footer + linhas. As linhas de maior prioridade
    // entram primeiro; a saída final é reagrupada por tipo.
    // Reserva também a linha de "N refs omitidas" (pode entrar depois das
    // linhas escolhidas e estouraria o teto).
    val overhead = HEADER.length + FOOTER.length + 4 + 100
    val budget = (maxChars - overhead).coerceAtLeast(0)
    val chosen = mutableListOf<InventoryEntry>()
    var used = 0
    for (entry in entries.sortedWith(compareBy({ it.priority }, { it.group.ordinal }, { it.line }))) {
        val cost = entry.line.length + 1
        if (used + cost > budget) break
        chosen += entry
        used += cost
    }
    val omitted = entries.size - chosen.size
    if (chosen.isEmpty()) {
        return NoteRefsInventory("", 0, omitted)
    }

    val body = buildString {
        append(HEADER).append('\n')
        for (group in InventoryGroup.values()) {
            val items = chosen.filter { it.group == group }
            if (items.isEmpty()) continue
            append(group.title).append('\n')
            items.forEach { append(it.line).append('\n') }
        }
        if (omitted > 0) {
            append("- … ").append(omitted)
                .append(" ref(s) omitida(s) do inventário (prioridade: foco, não resolvidas, Bíblia)\n")
        }
        append(FOOTER)
    }
    return NoteRefsInventory(body, chosen.size, omitted)
}

private fun NoteSource.isTraining(): Boolean =
    SourceType.fromBaseSlot(publicationKey) == SourceType.TRAINING

/**
 * Menor = entra antes no orçamento:
 * 0 foco/não resolvida; 1 Bíblia; 2 conteúdo resolvido; 3 orientação resolvida.
 */
private fun priorityOf(source: NoteSource, focusKeys: Set<String>): Int {
    val focus = isFocus(source, focusKeys)
    val unresolved = source.status.isMissing() || source.status.isPending()
    if (focus || unresolved) return 0
    return when {
        source.kind == SourceKind.BIBLE -> 1
        source.isTraining() -> 3
        else -> 2
    }
}

private fun isFocus(source: NoteSource, focusKeys: Set<String>): Boolean {
    if (focusKeys.isEmpty()) return false
    val key = source.refStatus?.let { NoteRefsCollector.sourceKey(it.ref) }
    return (key != null && key in focusKeys) ||
        source.ref in focusKeys ||
        (source.editionKey != null && source.editionKey in focusKeys)
}

private fun statusLabel(source: NoteSource): String = when (source.kind) {
    SourceKind.BIBLE -> when (source.status) {
        SourceStatus.RESOLVED -> "TNM resolvida"
        SourceStatus.TEXT_NOT_FOUND -> "TNM presente; versículo não encontrado"
        SourceStatus.EDITION_MISSING -> "TNM ausente"
        SourceStatus.DOWNLOADING -> "TNM baixando"
        SourceStatus.INDEXING -> "TNM indexando"
        SourceStatus.INDEX_FAILED -> "TNM importada; indexação falhou"
        SourceStatus.UNSUPPORTED -> "TNM em formato não suportado"
    }
    SourceKind.PUBLICATION -> when (source.status) {
        SourceStatus.RESOLVED -> "edição no acervo; trecho resolvido"
        SourceStatus.TEXT_NOT_FOUND -> "edição no acervo; trecho não encontrado"
        SourceStatus.EDITION_MISSING -> "edição ausente do acervo"
        SourceStatus.DOWNLOADING -> "baixando — acompanhe na Biblioteca"
        SourceStatus.INDEXING -> "indexando — o Copilot usa quando terminar"
        SourceStatus.INDEX_FAILED -> "edição importada; indexação falhou"
        SourceStatus.UNSUPPORTED -> "edição em formato não suportado"
    }
}
