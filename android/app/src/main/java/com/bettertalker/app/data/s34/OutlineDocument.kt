package com.bettertalker.app.data.s34

import com.bettertalker.app.data.util.RefDetector

/**
 * Fase 19-B.2 — modelo estrutural do esboço S-34.
 *
 * Representação explícita de objetivo, pontos, subpontos, ordem e
 * referências vinculadas — o que chunks + refs globais (F19-A) não têm.
 * Puro (data classes); persistência é trabalho da F19-B.3. Tipos primitivos
 * e listas => trivialmente serializável.
 *
 * Correspondência conceitual (nomes adaptados para não colidir com o
 * legado `OutlineSection` de OutlineParser.kt):
 * OutlineDocument ~ S34Document, sections ~ sections,
 * subsections ~ subsections, references ~ references.
 */
data class S34Document(
    /** Estável e determinístico: "s34-" + FNV-1a do texto bruto. */
    val id: String,
    val symbol: String = "S-34",
    val title: String,
    /** Null quando não há bloco de objetivo identificável — nunca inventado. */
    val objective: String?,
    val sections: List<S34Section>,
    /** Linhas órfãs antes da primeira seção (ex.: marcador, cabeçalho).
     * Preservadas sem provenance fina: são texto não-estruturado. */
    val headerLines: List<String> = emptyList(),
    val source: String = "S34"
) {
    /**
     * Índice global DERIVADO (§12 F19-B.2): fonte de verdade são as
     * referências das seções/subseções; isto é só travessia ordenada.
     */
    val references: List<S34Reference>
        get() = sections.flatMap { s ->
            (s.references + s.subsections.flatMap { it.references }).sortedBy { it.order }
        }
}

data class S34Section(
    /** "sec-1", estável dentro do parse (derivado da ordem). */
    val id: String,
    /** 1-based, ordem de encontro no documento — NUNCA por score. */
    val order: Int,
    val title: String,
    /** Minutos do marcador "(N min)" quando presente; senão null. */
    val minutes: Int? = null,
    /** Corpo integral verbatim (linhas de ref incluídas — rastreabilidade). */
    val content: String,
    val subsections: List<S34Subsection> = emptyList(),
    val references: List<S34Reference> = emptyList(),
    /** Linha 1-based do cabeçalho do ponto no texto original. */
    val sourceLine: Int,
    val source: String = "S34"
)

data class S34Subsection(
    /** "sec-2-1": pai + ordem local. */
    val id: String,
    /** 1-based dentro da seção pai. */
    val order: Int,
    /** Linha integral verbatim. */
    val content: String,
    val references: List<S34Reference> = emptyList(),
    /** Linha 1-based no texto original. */
    val sourceLine: Int,
    val source: String = "S34"
)

enum class S34RefType { BIBLE, PUBLICATION }

data class S34Reference(
    val type: S34RefType,
    val rawText: String,
    /** bible: "Rótulo|cap|vers"; publication: texto normalizado. */
    val normalizedReference: String,
    /** Sequência global do documento (ordem de aparição). */
    val order: Int,
    /** Linha 1-based no texto original. */
    val sourceLine: Int,
    val source: String = "S34",
    /** Pass-through dos detectores existentes (sem re-parse). */
    val bible: RefDetector.BibleRef? = null,
    val publication: RefDetector.DetectedRef? = null
)
