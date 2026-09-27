package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.repo.S34StructuralRetriever
import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.s34.S34StructuralRetrieval

/**
 * Fase 19-B.5 — contexto estrutural do S-34 (consome a B.4).
 *
 * Transforma o resultado do retrieval estrutural em um modelo legível e
 * determinístico para o prompt. Regras:
 * - a ORDEM vem do S-34 (nunca score);
 * - o ponto atual é marcado explicitamente quando existe; quando a B.4
 *   devolve estado negativo, isso é dito — nunca se finge um foco (§32);
 * - referências aparecem junto do dono que lhes deu origem (§9);
 * - provenance por rótulo textual (`[S34]`, `[BIBLE]`, `[PUBLICATION]`),
 *   não por emoji (§33);
 * - sem S-34 não existe bloco (caminho legado intacto, §31).
 *
 * Puro/testável: sem IO, sem LLM.
 */
data class OutlineStructureContext(
    val outlineId: String,
    val title: String,
    /** Null quando o S-34 não declara objetivo — nunca inventado (§19 B.5). */
    val objective: String?,
    /** Pontos em ordem documental (títulos; corpo só do foco). */
    val orderedSections: List<OrderedSection>,
    /** Ponto em foco, quando a B.4 resolveu um. */
    val currentSection: CurrentSection?,
    /** Estado explícito quando não há foco resolvido (§32). */
    val focusState: FocusState
) {
    data class OrderedSection(
        val id: String,
        val order: Int,
        val title: String,
        val minutes: Int?,
        val isCurrent: Boolean
    )

    data class CurrentSection(
        val id: String,
        val order: Int,
        val title: String,
        val content: String,
        val subsections: List<CurrentSubsection>,
        val references: List<StructuralReference>
    )

    data class CurrentSubsection(
        val id: String,
        val order: Int,
        val content: String,
        val references: List<StructuralReference>
    )

    data class StructuralReference(
        val type: S34RefType,
        val rawText: String,
        /** Dono real: id da seção ou da subseção (§9). */
        val ownerId: String,
        val sourceLine: Int
    )

    enum class FocusState { SECTION, DOCUMENT, UNKNOWN_SECTION, UNMATCHED_HINT }
}

/**
 * Monta o contexto estrutural a partir do resultado da B.4.
 *
 * [document] é a fonte da LISTA ORDENADA COMPLETA (§7/§16 B.5): mesmo com
 * foco em um ponto, o modelo precisa saber que "o ponto 2 vem depois do 1 e
 * antes do 3". `NoOutline` devolve `null` (caminho legado).
 */
fun structuralContextOf(
    result: S34StructuralRetriever.Result,
    document: S34Document? = null
): OutlineStructureContext? {
    if (result is S34StructuralRetriever.Result.NoOutline) return null
    val outlineId = when (result) {
        is S34StructuralRetriever.Result.SectionFocus -> result.view.outlineId
        is S34StructuralRetriever.Result.DocumentScope -> result.outlineId
        is S34StructuralRetriever.Result.UnknownSection -> result.outlineId
        is S34StructuralRetriever.Result.UnmatchedSection -> result.outlineId
        is S34StructuralRetriever.Result.NoOutline -> return null
    }
    val views = when (result) {
        is S34StructuralRetriever.Result.SectionFocus -> listOf(result.view)
        is S34StructuralRetriever.Result.DocumentScope -> result.sections
        else -> emptyList()
    }
    val currentId = when (result) {
        is S34StructuralRetriever.Result.SectionFocus -> result.view.sectionId
        else -> null
    }
    val focus = when (result) {
        is S34StructuralRetriever.Result.SectionFocus ->
            OutlineStructureContext.FocusState.SECTION
        is S34StructuralRetriever.Result.DocumentScope ->
            OutlineStructureContext.FocusState.DOCUMENT
        is S34StructuralRetriever.Result.UnknownSection ->
            OutlineStructureContext.FocusState.UNKNOWN_SECTION
        is S34StructuralRetriever.Result.UnmatchedSection ->
            OutlineStructureContext.FocusState.UNMATCHED_HINT
        is S34StructuralRetriever.Result.NoOutline -> return null
    }
    // Lista ordenada COMPLETA vem do documento; sem documento (ex.: teste
    // isolado) cai para o que o escopo trouxe, ainda em ordem.
    val ordered = document?.let { d ->
        S34StructuralRetrieval.sectionRefs(d).map { ref ->
            OutlineStructureContext.OrderedSection(
                id = ref.id,
                order = ref.order,
                title = ref.title,
                minutes = d.sections.firstOrNull { it.id == ref.id }?.minutes,
                isCurrent = ref.id == currentId
            )
        }
    } ?: views.sortedBy { it.documentOrder }.map { v ->
        OutlineStructureContext.OrderedSection(
            id = v.sectionId, order = v.documentOrder, title = v.title,
            minutes = v.minutes, isCurrent = v.sectionId == currentId
        )
    }
    val current = views.firstOrNull { it.sectionId == currentId }
    return OutlineStructureContext(
        outlineId = outlineId,
        title = document?.title.orEmpty(),
        objective = document?.objective?.takeIf { it.isNotBlank() },
        orderedSections = ordered,
        currentSection = current?.let { v ->
            OutlineStructureContext.CurrentSection(
                id = v.sectionId,
                order = v.documentOrder,
                title = v.title,
                content = v.entries.firstOrNull {
                    it.kind == S34StructuralRetrieval.EntryKind.SECTION
                }?.text.orEmpty(),
                subsections = v.entries
                    .filter { it.kind == S34StructuralRetrieval.EntryKind.SUBSECTION }
                    .sortedBy { it.sourceLine }
                    .mapIndexed { idx, e ->
                        OutlineStructureContext.CurrentSubsection(
                            id = e.ownerId,
                            order = idx + 1,
                            content = e.text,
                            references = refsOf(v, e.ownerId)
                        )
                    },
                references = refsOf(v, v.sectionId)
            )
        },
        focusState = focus
    )
}

private fun refsOf(
    v: S34StructuralRetrieval.ScopedView,
    ownerId: String
): List<OutlineStructureContext.StructuralReference> =
    v.entries.filter {
        it.kind == S34StructuralRetrieval.EntryKind.REFERENCE && it.ownerId == ownerId
    }.map { e ->
        OutlineStructureContext.StructuralReference(
            type = e.refType ?: S34RefType.PUBLICATION,
            rawText = e.text,
            ownerId = e.ownerId,
            sourceLine = e.sourceLine
        )
    }

// ---------- Serialização para o prompt (§§5, 9, 33) ----------

private const val S34 = "[S34]"

/**
 * Renderiza o bloco estrutural. Estrutura explícita, nunca texto amorfo (§5).
 * Limites (§21): pergunta geral = objetivo + lista ordenada; foco de seção =
 * lista ordenada + ponto atual completo (corpo, subpontos, referências).
 */
fun serializeStructuralContext(ctx: OutlineStructureContext): String {
    val sb = StringBuilder()
    sb.append("\n\n--- S-34 (ESTRUTURA DO DISCURSO) ---\n")
    sb.append("$S34 Título: ${ctx.title.ifBlank { "(sem título)" }}\n")
    if (ctx.objective != null) sb.append("$S34 Objetivo: ${ctx.objective}\n")

    if (ctx.orderedSections.isNotEmpty()) {
        sb.append("$S34 Pontos, na ordem do esboço:\n")
        ctx.orderedSections.forEach { s ->
            val min = s.minutes?.let { " ($it min)" } ?: ""
            val mark = if (s.isCurrent) "  <= PONTO ATUAL" else ""
            sb.append("$S34   ${s.order}. ${s.title}$min$mark\n")
        }
    } else {
        sb.append("$S34 (lista de pontos indisponível neste estado)\n")
    }

    when (ctx.focusState) {
        OutlineStructureContext.FocusState.SECTION -> {
            val c = ctx.currentSection
            if (c != null) {
                sb.append("$S34 PONTO ATUAL (${c.order}): ${c.title}\n")
                if (c.content.isNotBlank()) sb.append("$S34   Corpo do ponto: ${c.content}\n")
                c.subsections.forEach { sub ->
                    sb.append("$S34   Subponto ${sub.order}: ${sub.content}\n")
                    sub.references.forEach { r ->
                        sb.append("$S34     ${label(r.type)} ${r.rawText}" +
                            " (vinculada ao subponto ${sub.order}; linha ${r.sourceLine})\n")
                    }
                }
                c.references.forEach { r ->
                    sb.append("$S34   ${label(r.type)} ${r.rawText}" +
                        " (vinculada ao ponto ${c.order}; linha ${r.sourceLine})\n")
                }
                if (c.references.isEmpty() && c.subsections.all { it.references.isEmpty() }) {
                    sb.append("$S34   (nenhuma referência vinculada a este ponto)\n")
                }
            }
        }
        OutlineStructureContext.FocusState.DOCUMENT ->
            // Estrutura geral sem corpo: respeita §21 e evita duplicação.
            sb.append("$S34 Ponto atual: nenhum selecionado (pergunta sobre o discurso)\n")
        OutlineStructureContext.FocusState.UNKNOWN_SECTION ->
            sb.append("$S34 Ponto atual: NÃO IDENTIFICADO — a seção pedida não pertence" +
                " a este esboço. Não presuma um ponto.\n")
        OutlineStructureContext.FocusState.UNMATCHED_HINT ->
            sb.append("$S34 Ponto atual: NÃO IDENTIFICADO — nenhum ponto corresponde à" +
                " referência fornecida. Não presuma um ponto.\n")
    }
    sb.append("$S34 Fim da estrutura. A ordem acima é a ordem do discurso.\n")
    sb.append("--- FIM DA ESTRUTURA DO S-34 ---\n")
    return sb.toString()
}

private fun label(t: S34RefType): String =
    if (t == S34RefType.BIBLE) "[BIBLE]" else "[PUBLICATION]"

/**
 * Cláusula de comportamento do S-34 (§§12-15, 34-35). Regras de contrato —
 * testadas como regras, não como texto decorativo.
 */
val S34_PROMPT_RULES = """
REGRAS DO S-34 (quando houver estrutura de S-34 no contexto):
1. O S-34 é a fonte estrutural deste discurso.
2. Preserve a ordem dos pontos fornecida pelo S-34 — não reorganize, não antecipe
   pontos posteriores e não volte a pontos anteriores sem pedido explícito.
3. Não invente novos pontos, subpontos ou referências.
4. Trate as referências como pertencentes ao ponto/subponto indicado; não as mova.
5. Use o S-34, a Bíblia e as publicações referenciadas como fontes de CONTEÚDO do
   que falar. Use BE/TH apenas para orientar COMO apresentar.
6. Nunca use BE/TH como fonte factual.
7. Criatividade serve para formular exemplos, aplicações, transições e maneiras de
   apresentar — nunca apresente criação sua como informação factual vinda do S-34,
   da Bíblia ou de uma publicação.
8. Quando as fontes não sustentarem uma afirmação factual, não preencha a lacuna
   inventando conteúdo; diga exatamente: "$INSUFFICIENT_EVIDENCE_MESSAGE"
""".trimIndent()
