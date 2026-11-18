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

/** Proveniência mínima exigida para um item entrar no corpus factual. */
data class EvidenceProvenance(
    val sourceId: String,
    val category: EvidenceCategory,
    val documentUnit: String? = null,
    val recoveredText: String = "",
    val resolved: Boolean = false,
    val waived: Boolean = false,
) {
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

/** Item de contexto com proveniência explícita. */
data class EvidenceItem(
    val id: String,
    val reference: String,
    val text: String,
    val provenance: EvidenceProvenance,
) {
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

    fun documentaryTexts(): List<String> = documentaryItems.map { it.text }

    fun methodTexts(): List<String> = methodItems.map { it.text }

    fun isEmpty(): Boolean = items.isEmpty()

    fun hasDocumentaryEvidence(): Boolean = documentaryItems.isNotEmpty()
}

/**
 * Citação atribuída ao usuário. Deve estar relacionada ao conteúdo real da
 * mensagem, não apenas a um marcador gerado pela IA.
 */
data class AttributedUserQuote(
    val quote: String,
    val sourceMessage: String,
) {
    fun isGroundedInMessage(): Boolean =
        quote.isNotBlank() &&
            sourceMessage.isNotBlank() &&
            sourceMessage.contains(quote.trim(), ignoreCase = true)
}
