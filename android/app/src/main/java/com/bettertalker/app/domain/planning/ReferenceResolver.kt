package com.bettertalker.app.domain.planning

/**
 * Resolve uma referência do esboço para o texto literal do acervo local.
 *
 * NÃO usa RetrievalScope/retrieval por similaridade — usa busca exata
 * (`findByRef` para Bible; filtro por symbol + `searchLikeIn` para publicações).
 *
 * Se não encontra, retorna [ResolvedReference] com [ReferenceStatus]
 * explicando por quê (nunca lança por ref desconhecida).
 */
interface ReferenceResolver {
    suspend fun resolveBible(ref: String): ResolvedReference
    suspend fun resolvePublication(ref: PublicationRef): ResolvedReference
}

/**
 * Resultado da resolução.
 *
 * @param original ref como veio do esboço
 * @param canonicalRef ref normalizada (null se não normalizável)
 * @param text texto literal do acervo (null se não encontrado)
 * @param passageId id do PassageEntity (null se não encontrado)
 * @param status RESOLVED / PARTIAL / UNRESOLVED / MISSING_CORPUS
 */
data class ResolvedReference(
    val original: String,
    val canonicalRef: String?,
    val text: String?,
    val passageId: String?,
    val status: ReferenceStatus,
)

enum class ReferenceStatus {
    /** Match exato no acervo. */
    RESOLVED,
    /** Match aproximado (publicação por similaridade). */
    PARTIAL,
    /** Ref válida mas não encontrada no acervo. */
    UNRESOLVED,
    /** Ref não normalizável (formato desconhecido). */
    MISSING_CORPUS,
}
