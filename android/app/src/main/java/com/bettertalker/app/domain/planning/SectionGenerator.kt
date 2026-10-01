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
}
