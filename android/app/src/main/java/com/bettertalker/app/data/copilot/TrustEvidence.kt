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

    fun documentaryTexts(): List<String> = documentaryItems.map { it.text }

    fun methodTexts(): List<String> = methodItems.map { it.text }

    fun isEmpty(): Boolean = items.isEmpty()

    fun hasDocumentaryEvidence(): Boolean = documentaryItems.isNotEmpty()

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
