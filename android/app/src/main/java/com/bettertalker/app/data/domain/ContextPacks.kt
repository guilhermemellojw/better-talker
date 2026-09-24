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
        trainingLimit: Int = 4
    ): ContextPack = ContextPack(
        contentSources = content.take(contentLimit.coerceAtLeast(0)).mapIndexed { i, c ->
            candidateToEvidence(c, "Fonte ${i + 1}")
        },
        trainingSources = training.take(trainingLimit.coerceAtLeast(0)).mapIndexed { i, c ->
            candidateToEvidence(c, "Treinamento ${i + 1}")
        }
    )

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
