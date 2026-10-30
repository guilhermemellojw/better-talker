package com.bettertalker.app.domain.planning

/**
 * Gera um draft para uma seção/sub-ponto a partir do dossiê.
 *
 * Abstração pura (LlmProvider entra por injeção na impl).
 *
 * Retorna null em falha tratável (rate limit, JSON inválido, resposta vazia).
 * CancellationException SEMPRE propaga (structured concurrency).
 */
interface SectionGenerator {
    suspend fun generate(dossier: Dossier): SectionDraft?

    /**
     * Mesma geração, reportando o progresso por parte (45 min monolítico).
     * O default preserva fakes antigos (sem progresso).
     */
    suspend fun generateWithProgress(
        dossier: Dossier,
        onProgress: (DraftProgress) -> Unit,
    ): SectionDraft? = generate(dossier)

    /**
     * Continua um draft curto/cortado a partir do texto atual. Null quando
     * a implementação não suporta ou falha de forma tratável.
     */
    suspend fun continueDraft(
        dossier: Dossier,
        currentText: String,
        currentUsedSources: List<String> = emptyList(),
        targetWords: Int = 0,
    ): SectionDraft? = null
}
