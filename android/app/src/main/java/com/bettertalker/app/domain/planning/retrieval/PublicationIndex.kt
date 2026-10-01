package com.bettertalker.app.domain.planning.retrieval

import com.bettertalker.app.domain.planning.PublicationRef

/**
 * Índice de publicações consultável por tema.
 *
 * Implementações reais leem o acervo local indexado (BYOD).
 */
interface PublicationIndex {
    /**
     * Retorna referências a publicações relacionadas ao tema.
     * Deve retornar no máximo [limit] itens.
     * Pode retornar lista vazia se nada for encontrado.
     */
    suspend fun findByTheme(theme: String, limit: Int): List<PublicationRef>
}
