package com.bettertalker.app.domain.planning.retrieval

/**
 * Índice de textos bíblicos consultável por tema.
 *
 * Implementações reais leem o índice local (TNM 2015, BYOD).
 */
interface BibleIndex {
    /**
     * Retorna referências bíblicas (ex.: "Gê 3:6", "Rm 5:12") relacionadas ao tema.
     * Deve retornar no máximo [limit] itens.
     * Pode retornar lista vazia se nada for encontrado.
     */
    suspend fun findByTheme(theme: String, limit: Int): List<String>
}
