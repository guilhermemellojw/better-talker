package com.bettertalker.app.data.copilot

/**
 * TRUST-1A1 — base tipada e testável do isolamento de evidências.
 *
 * Esta etapa NÃO altera rotas, verificadores, prompts, RAG, Room ou UX.
 * Apenas define os tipos de domínio e as regras puras de proveniência.
 *
 * Invariante central:
 * - Contexto conversacional, memória, estrutura e treinamento NÃO são
 *   evidência documental por construção.
 */

/** Categoria de origem de um item de contexto. */
enum class EvidenceCategory {
    /** Fonte documental recuperada (Bíblia/publicação com texto). */
    CONTENT,

    /** Orientação de oratória (be/th). Nunca prova doutrinal/histórica. */
    TRAINING,

    /** Estrutura do discurso (títulos, objetivos, refs sem texto). */
    STRUCTURAL,

    /** Fala do usuário, histórico e hipóteses. Nunca é evidência. */
    CONVERSATION,

    /** Memória de redação (decisões/preferências/abordagem). Nunca é evidência. */
    WRITING_MEMORY,
}

/**
 * Proveniência mínima exigida para um item entrar no corpus factual.
 *
 * `resolved` é uma informação fornecida por uma etapa de recuperação.
 * Ela NÃO é prova autossuficiente de que o texto veio do jw.org; a futura
 * integração deve construir evidências a partir de resultados de recuperação
 * confiáveis, nunca de strings livres ou respostas do modelo.
 */
data class EvidenceProvenance(
    val sourceId: String,
    val category: EvidenceCategory,
    val documentUnit: String? = null,
    val recoveredText: String = "",
    val resolved: Boolean = false,
    val waived: Boolean = false,
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId não pode ser vazio" }
    }

    /**
     * Um item é evidência documental apenas quando:
     * - é CONTENT;
     * - não foi dispensado;
     * - tem texto efetivamente recuperado;
     * - está marcado como resolvido.
     */
    fun isDocumentaryEvidence(): Boolean =
        category == EvidenceCategory.CONTENT &&
            !waived &&
            resolved &&
            recoveredText.isNotBlank()

    /**
     * Treinamento pode sustentar orientação de técnica oratória, mas nunca
     * afirmação doutrinal, bíblica ou histórica.
     */
    fun isMethodEvidence(): Boolean =
        category == EvidenceCategory.TRAINING &&
            !waived &&
            resolved &&
            recoveredText.isNotBlank()
}

/**
 * Item de contexto com proveniência explícita.
 *
 * O texto documental canônico é [EvidenceProvenance.recoveredText].
 * Não existe uma segunda representação independente: isso evita que um texto
 * não recuperado seja apresentado como evidência.
 */
data class EvidenceItem(
    val id: String,
    val reference: String,
    val provenance: EvidenceProvenance,
) {
    init {
        require(id.isNotBlank()) { "id não pode ser vazio" }
        require(reference.isNotBlank()) { "reference não pode ser vazio" }
    }

    /** Texto documental canônico, derivado da proveniência. */
    val text: String
        get() = provenance.recoveredText

    fun isDocumentaryEvidence(): Boolean = provenance.isDocumentaryEvidence() && text.isNotBlank()

    fun isMethodEvidence(): Boolean = provenance.isMethodEvidence() && text.isNotBlank()
}

/** Contexto conversacional: prompt-only, nunca evidência. */
data class ConversationContext(
    val userText: String = "",
    val history: List<String> = emptyList(),
    val hypotheses: List<String> = emptyList(),
)

/** Memória de redação: prompt-only, nunca evidência. */
data class WritingMemory(
    val decisions: List<String> = emptyList(),
    val agreedApproach: String? = null,
)

/** Estrutura do discurso: metadados, não prova documental. */
data class StructuralContext(
    val objective: String? = null,
    val sectionTitles: List<String> = emptyList(),
    val references: List<String> = emptyList(),
)

/** Corpus factual: apenas itens com proveniência documental válida. */
data class EvidenceCorpus(
    val items: List<EvidenceItem> = emptyList(),
) {
    val documentaryItems: List<EvidenceItem>
        get() = items.filter { it.isDocumentaryEvidence() }

    val methodItems: List<EvidenceItem>
        get() = items.filter { it.isMethodEvidence() }

    val structuralItems: List<EvidenceItem>
        get() = items.filter { it.provenance.category == EvidenceCategory.STRUCTURAL }

    fun documentaryTexts(): List<String> = documentaryItems.map { it.text }

    fun methodTexts(): List<String> = methodItems.map { it.text }

    fun structuralTexts(): List<String> = structuralItems.map { it.text }

    fun isEmpty(): Boolean = items.isEmpty()

    fun hasDocumentaryEvidence(): Boolean = documentaryItems.isNotEmpty()

    /**
     * Corpus documental vazio significa "sem verificação factual", nunca
     * "fato confirmado". Ausência de aviso sintático não é prova de apoio.
     */
    fun hasNoDocumentaryEvidence(): Boolean = documentaryItems.isEmpty()

    fun containsOnlyTrainingEvidence(): Boolean =
        items.isNotEmpty() && items.all { it.provenance.category == EvidenceCategory.TRAINING }

    fun containsOnlyStructuralContext(): Boolean =
        items.isNotEmpty() && items.all { it.provenance.category == EvidenceCategory.STRUCTURAL }
}

/**
 * Extrato documental com vínculo verificável ao texto recuperado.
 */
data class EvidenceExcerpt(
    val excerpt: String,
    val sourceText: String,
) {
    fun isLinkedToSource(): Boolean =
        excerpt.isNotBlank() &&
            sourceText.isNotBlank() &&
            sourceText.contains(excerpt.trim(), ignoreCase = true)
}

/**
 * Fonte documental recuperada, com referência e origem verificáveis.
 * Nunca deve ser criada a partir de texto livre, alias ou resposta do modelo.
 */
data class RecoveredDocument(
    val sourceId: String,
    val reference: String,
    val text: String,
    val documentUnit: String? = null,
    val resolved: Boolean = true,
    val waived: Boolean = false,
    /** Recuperação parcial/incompleta; nunca promove a documento completo. */
    val partial: Boolean = false,
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId não pode ser vazio" }
        require(reference.isNotBlank()) { "reference não pode ser vazio" }
        require(text.isNotBlank()) { "text não pode ser vazio" }
    }

    fun isDocumentaryEligible(): Boolean =
        resolved && !waived && !partial && text.isNotBlank()
}

/**
 * Adapter para linhas produzidas por recuperação rastreável no formato
 * "[ref] texto". Retorna null se a linha não tiver ref e texto recuperado.
 */
fun recoveredDocumentFromResolvedBible(
    ref: String,
    resolved: com.bettertalker.app.domain.planning.ResolvedReference,
): com.bettertalker.app.data.copilot.RecoveredDocument? {
    if (ref.isBlank()) return null
    val text = resolved.text?.takeIf { it.isNotBlank() } ?: return null
    val canonical = resolved.canonicalRef?.takeIf { it.isNotBlank() } ?: ref
    if (canonical.isBlank()) return null
    return com.bettertalker.app.data.copilot.RecoveredDocument(
        sourceId = canonical,
        reference = canonical,
        text = text,
        documentUnit = resolved.passageId,
        resolved = resolved.status == com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED ||
            resolved.status == com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL,
        waived = false,
        partial = resolved.status == com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL,
    )
}

/**
 * Adapter puro para uma passagem recuperada. Retorna null para ref/texto
 * vazios, sem lançar exceção para hits incompletos.
 */
fun recoveredDocumentFromPassage(
    reference: String,
    text: String,
    documentUnit: String? = null,
): com.bettertalker.app.data.copilot.RecoveredDocument? {
    val ref = reference.trim()
    val body = text.trim()
    if (ref.isBlank() || body.isBlank()) return null
    return com.bettertalker.app.data.copilot.RecoveredDocument(
        sourceId = ref,
        reference = ref,
        text = body,
        documentUnit = documentUnit,
        resolved = true,
        waived = false,
    )
}

fun recoveredDocumentsFromDossier(
    dossier: com.bettertalker.app.domain.planning.Dossier,
): List<com.bettertalker.app.data.copilot.RecoveredDocument> = buildList {
    dossier.bibleTexts.forEach { resolved ->
        val ref = resolved.ref.trim()
        if (ref.isBlank()) return@forEach
        val text = resolved.text?.takeIf { it.isNotBlank() } ?: return@forEach
        if (resolved.status != com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED &&
            resolved.status != com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL
        ) return@forEach
        add(
            com.bettertalker.app.data.copilot.RecoveredDocument(
                sourceId = "dossier:bible:$ref",
                reference = ref,
                text = text,
                resolved = true,
                waived = false,
                partial = resolved.status ==
                    com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL,
            )
        )
    }
    dossier.publicationTexts.forEach { resolved ->
        val ref = resolved.ref.symbol.trim()
        if (ref.isBlank()) return@forEach
        val text = resolved.text?.takeIf { it.isNotBlank() } ?: return@forEach
        if (resolved.status != com.bettertalker.app.domain.planning.ReferenceStatus.RESOLVED &&
            resolved.status != com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL
        ) return@forEach
        add(
            com.bettertalker.app.data.copilot.RecoveredDocument(
                sourceId = "dossier:pub:$ref",
                reference = ref,
                text = text,
                resolved = true,
                waived = false,
                partial = resolved.status ==
                    com.bettertalker.app.domain.planning.ReferenceStatus.PARTIAL,
            )
        )
    }
}

/**
 * Citação atribuída ao usuário. Deve estar relacionada ao conteúdo real da
 * mensagem fornecida por um consumidor confiável.
 *
 * `trustedOriginalMessage` não deve ser autodeclarado pelo modelo; deve vir
 * de uma fonte confiável de mensagem.
 */
data class AttributedUserQuote(
    val quote: String,
    val trustedOriginalMessage: String,
) {
    fun isGroundedInMessage(): Boolean =
        quote.isNotBlank() &&
            trustedOriginalMessage.isNotBlank() &&
            trustedOriginalMessage.contains(quote.trim(), ignoreCase = true)

    /** Uma citação de usuário nunca é evidência bíblica/documental. */
    val canSupportFactualClaim: Boolean = false
}
