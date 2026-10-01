package com.bettertalker.app.domain.planning.retrieval

/**
 * Índice de princípios metodológicos de oratória (be/th) consultável por contexto.
 */
interface MethodIndex {
    /**
     * Retorna princípios de be/th (texto puro) aplicáveis ao contexto.
     * Deve retornar no máximo [limit] itens.
     * Pode retornar lista vazia se nada for encontrado.
     *
     * @param category serial de TrainingCategory (ex: "introduction",
     *   "development", "conclusion"). Null = sem boost. O domínio usa
     *   `String?` para não importar `data.domain`.
     */
    suspend fun findPrinciples(
        context: String,
        limit: Int,
        category: String? = null,
    ): List<String>
}
