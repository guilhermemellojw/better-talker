package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.repo.RetrievalRepository
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.retrieval.PublicationIndex

/**
 * Implementação de [PublicationIndex] sobre o índice local (passages).
 *
 * Escopo global: todos os attachments indexados com [SourceType.CONTENT].
 * Sem trilho content indexado, retorna lista vazia.
 * Tuplas (symbol, page, paragraph) duplicadas são deduplicadas;
 * symbols em branco são filtrados.
 */
class RoomPublicationIndex(
    private val retrievalRepository: RetrievalRepository,
    private val attachmentDao: AttachmentDao,
) : PublicationIndex {

    override suspend fun findByTheme(theme: String, limit: Int): List<PublicationRef> {
        val contentIds = attachmentDao.indexedIdsOf(SourceType.CONTENT)
        if (contentIds.isEmpty()) return emptyList()
        val result = retrievalRepository.retrieve(
            query = theme,
            scope = RetrievalScope(contentSourceIds = contentIds, trainingSourceIds = emptyList()),
            limit = limit,
        )
        if (result.status != RetrievalStatus.OK) return emptyList()
        return result.hits
            .mapNotNull { candidate ->
                val symbol = candidate.passage.symbol?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                PublicationRef(
                    symbol = symbol,
                    page = candidate.passage.page,
                    paragraph = candidate.passage.paragraph,
                )
            }
            .distinctBy { Triple(it.symbol, it.page, it.paragraph) }
            .take(limit.coerceAtLeast(0))
    }
}
