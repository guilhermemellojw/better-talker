package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.repo.TrainingRepository
import com.bettertalker.app.domain.planning.retrieval.MethodIndex

/**
 * Implementação de [MethodIndex] sobre o índice local (passages do trilho training).
 *
 * Escopo global: todos os attachments indexados com [SourceType.TRAINING].
 * Sem trilho training indexado, retorna lista vazia.
 * Textos duplicados são deduplicados; textos em branco são filtrados.
 */
class RoomMethodIndex(
    private val trainingRepository: TrainingRepository,
    private val attachmentDao: AttachmentDao,
) : MethodIndex {

    override suspend fun findPrinciples(
        context: String,
        limit: Int,
        category: String?,
    ): List<String> {
        val trainingIds = attachmentDao.indexedIdsOf(SourceType.TRAINING)
        if (trainingIds.isEmpty()) return emptyList()
        val result = trainingRepository.retrieveTraining(
            query = context,
            // fromSerial(null/lixo) → UNKNOWN → retrieveTraining trata como sem boost.
            category = TrainingCategory.fromSerial(category),
            scope = RetrievalScope(contentSourceIds = emptyList(), trainingSourceIds = trainingIds),
            limit = limit,
        )
        if (result.status != RetrievalStatus.OK) return emptyList()
        return result.hits
            .mapNotNull { it.passage.text.takeIf { t -> t.isNotBlank() } }
            .distinct()
            .take(limit.coerceAtLeast(0))
    }
}
