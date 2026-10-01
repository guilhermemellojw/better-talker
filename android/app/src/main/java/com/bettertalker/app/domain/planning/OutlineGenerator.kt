package com.bettertalker.app.domain.planning

/**
 * Abstração do gerador de propostas. Implementações reais usam LLM (Groq/Qwen),
 * mas esta interface é pura e testável com fakes.
 *
 * Retorna null se o gerador não conseguir produzir uma proposta.
 */
interface OutlineGenerator {
    /**
     * Gera uma proposta de esboço para [request].
     *
     * @return a proposta gerada, ou null se a geração falhar de forma tratável.
     */
    suspend fun generate(request: OutlineGenerationRequest): OutlineProposal?
}
