package com.bettertalker.app.data.domain

/**
 * Montagem de ContextPack — trilhos explicitamente separados (§12 Fase 7).
 * factual claim => só content (training_sources vazio); apresentação =>
 * ambos, sem misturar. Sem IO, sem Android.
 */
object ContextPacks {

    fun fromCandidates(
        content: List<RetrievalCandidate>,
        training: List<RetrievalCandidate>,
        contentLimit: Int = 8,
        trainingLimit: Int = 4,
        /** #15d-escopo: nº de hits relevantes do RAG (default preserva o legado). */
        relevantCount: Int = 0,
        /** v2.7.3: trechos exatos das citações do esboço (prioridade sobre o top-K). */
        pinned: List<EvidenceSource> = emptyList(),
        /** v2.7.3: citações sem texto no acervo (não citar como fato). */
        unavailableRefs: List<String> = emptyList(),
    ): ContextPack {
        val limit = contentLimit.coerceAtLeast(0)
        val ranked = content.take(limit).mapIndexed { i, c ->
            candidateToEvidence(c, "Fonte ${i + 1}")
        }
        val pinnedUnique = pinned.distinctBy { it.id }
        val pinnedKept = pinnedUnique.take(limit)
        val rankedKept = ranked
            .filter { r -> pinnedKept.none { it.id == r.id } }
            .take((limit - pinnedKept.size).coerceAtLeast(0))
        val contentSources = (pinnedKept + rankedKept).take(limit)
        // v2.7.3 (revisão): o excedente de CITAÇÕES não desaparece; vira
        // referência explícita "fora do orçamento" (texto existe, não coube).
        val omitted = pinnedUnique.drop(limit).map { it.reference }
        return ContextPack(
            contentSources = contentSources,
            trainingSources = training.take(trainingLimit.coerceAtLeast(0)).mapIndexed { i, c ->
                candidateToEvidence(c, "Treinamento ${i + 1}")
            },
            relevantCount = relevantCount,
            unavailableRefs = unavailableRefs,
            omittedRefs = omitted,
        )
    }

    /** Só conteúdo: o trilho training fica vazio por construção (§20). */
    fun contentOnly(candidates: List<RetrievalCandidate>, limit: Int = 8): ContextPack =
        fromCandidates(candidates, emptyList(), limit, 0)

    fun candidateToEvidence(c: RetrievalCandidate, fallbackRef: String): EvidenceSource {
        val p = c.passage
        return EvidenceSource(
            id = p.id,
            reference = p.ref.ifBlank { fallbackRef },
            text = p.text,
            sourceType = p.sourceType,
            publication = p.symbol ?: p.pubId,
            section = p.section.ifBlank { null },
            paragraph = p.paragraph,
            page = p.page,
            trainingCategory = p.trainingCategory,
            score = (c.finalScore * 100).toInt() / 100.0,
            matchedTerms = c.matchedTerms,
            foundBy = c.foundBy
        )
    }
}
