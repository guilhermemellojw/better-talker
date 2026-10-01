package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.repo.RetrievalRepository
import com.bettertalker.app.domain.planning.retrieval.BibleIndex

/** Ids indexados de um trilho (escopo global — nunca noteScope). O trilho deriva do baseSlot. */
internal suspend fun AttachmentDao.indexedIdsOf(track: SourceType): List<String> =
    all().filter { it.indexed && SourceType.fromBaseSlot(it.baseSlot) == track }.map { it.id }

/**
 * Implementação de [BibleIndex] sobre o índice local (passages).
 *
 * Escopo global: todos os attachments indexados com [SourceType.BIBLE].
 * Sem trilho bible indexado, retorna lista vazia.
 * Refs duplicadas são deduplicadas; refs em branco são filtradas.
 */
class RoomBibleIndex(
    private val retrievalRepository: RetrievalRepository,
    private val attachmentDao: AttachmentDao,
) : BibleIndex {

    override suspend fun findByTheme(theme: String, limit: Int): List<String> {
        val bibleIds = attachmentDao.indexedIdsOf(SourceType.BIBLE)
        if (bibleIds.isEmpty()) return emptyList()
        val result = retrievalRepository.retrieve(
            query = theme,
            scope = RetrievalScope(contentSourceIds = bibleIds, trainingSourceIds = emptyList()),
            limit = limit,
        )
        if (result.status != RetrievalStatus.OK) return emptyList()
        return result.hits
            .mapNotNull { it.passage.ref.takeIf { r -> r.isNotBlank() } }
            .distinct()
            .take(limit.coerceAtLeast(0))
    }
}
