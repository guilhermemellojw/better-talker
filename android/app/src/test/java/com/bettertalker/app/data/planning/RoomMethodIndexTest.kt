package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.RetrievalResult
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.repo.TrainingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MethodFakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
    override fun observe(): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
    override fun observeForNote(noteId: String): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
    override suspend fun all(): List<AttachmentEntity> = rows
    override suspend fun get(id: String): AttachmentEntity? = rows.firstOrNull { it.id == id }
    override suspend fun upsert(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
    override suspend fun update(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
    override suspend fun setIndexed(id: String, indexed: Boolean) {}
    override suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?) {}
    override suspend fun setBaseSlot(id: String, slot: String?) {}
    override suspend fun setNote(id: String, noteId: String?) {}
    override suspend fun baseReady(slot: String): AttachmentEntity? = null
    override suspend fun delete(id: String) { rows = rows.filter { it.id != id } }
    override suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?) {}
}

private class MethodFakeTraining(
    private val byAttachment: Map<String, List<RetrievalCandidate>> = emptyMap(),
) : TrainingRepository {
    var calls = 0
    var lastScope: RetrievalScope? = null
    var lastCategory: TrainingCategory? = null
    override suspend fun retrieveTraining(
        query: String,
        category: TrainingCategory?,
        scope: RetrievalScope,
        limit: Int,
    ): RetrievalResult {
        calls++
        lastScope = scope
        lastCategory = category
        val hits = scope.trainingSourceIds.flatMap { byAttachment[it].orEmpty() }
        if (hits.isEmpty()) return RetrievalResult(RetrievalStatus.EMPTY_CORPUS, emptyList())
        return RetrievalResult(RetrievalStatus.OK, hits)
    }
}

private fun methodAtt(id: String, slot: String?, indexed: Boolean = true) = AttachmentEntity(
    id = id, noteId = null, fileName = "$id.pdf", kind = "pdf", sizeBytes = 1,
    appPath = "/x", indexed = indexed, addedAt = 1L, baseSlot = slot,
)

private fun methodPassage(id: String, pub: String, text: String) = Passage(
    id = id, pubId = pub, text = text, normalizedText = text.lowercase(), ref = "ref $id",
    section = "", page = null, paragraph = null, order = 0,
    sourceType = SourceType.TRAINING, symbol = "be", trainingCategory = TrainingCategory.UNKNOWN,
)

private fun methodCandidate(passage: Passage) = RetrievalCandidate(
    passage = passage, publicationTitle = "be", lexicalScore = 0.5,
    metadataScore = 0.5, finalScore = 0.5, matchedTerms = emptyList(), foundBy = listOf("lexical"),
)

class RoomMethodIndexTest {

    @Test
    fun noIndexedTrainingAttachments_returnsEmpty() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("w", null), methodAtt("be", "be", indexed = false)))
        val repo = MethodFakeTraining()
        assertTrue(RoomMethodIndex(repo, atts).findPrinciples("ilustrações", 5).isEmpty())
        assertEquals(0, repo.calls)
    }

    @Test
    fun indexedTrainingAttachment_returnsTexts() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("be", "be")))
        val repo = MethodFakeTraining(
            mapOf("be" to listOf(
                methodCandidate(methodPassage("p1", "be", "Use ilustrações simples")),
            ))
        )
        val texts = RoomMethodIndex(repo, atts).findPrinciples("ilustrações", 5)
        assertEquals(listOf("Use ilustrações simples"), texts)
        assertEquals(listOf("be"), repo.lastScope?.trainingSourceIds)
    }

    @Test
    fun duplicateTexts_areDeduplicated() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("be", "be")))
        val repo = MethodFakeTraining(
            mapOf("be" to listOf(
                methodCandidate(methodPassage("p1", "be", "Mesmo princípio")),
                methodCandidate(methodPassage("p2", "be", "Mesmo princípio")),
            ))
        )
        assertEquals(listOf("Mesmo princípio"), RoomMethodIndex(repo, atts).findPrinciples("t", 5))
    }

    @Test
    fun blankTexts_areFiltered() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("be", "be")))
        val repo = MethodFakeTraining(
            mapOf("be" to listOf(
                methodCandidate(methodPassage("p1", "be", "   ")),
                methodCandidate(methodPassage("p2", "be", "Princípio válido")),
            ))
        )
        assertEquals(listOf("Princípio válido"), RoomMethodIndex(repo, atts).findPrinciples("t", 5))
    }

    @Test
    fun limitIsRespected() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("be", "be")))
        val repo = MethodFakeTraining(
            mapOf("be" to listOf(
                methodCandidate(methodPassage("p1", "be", "A")),
                methodCandidate(methodPassage("p2", "be", "B")),
                methodCandidate(methodPassage("p3", "be", "C")),
            ))
        )
        assertEquals(listOf("A", "B"), RoomMethodIndex(repo, atts).findPrinciples("t", 2))
    }

    @Test
    fun contentAttachments_areIgnored() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("be", "be"), methodAtt("w", null)))
        val repo = MethodFakeTraining(
            mapOf(
                "be" to listOf(methodCandidate(methodPassage("p1", "be", "Princípio be"))),
                "w" to listOf(methodCandidate(methodPassage("p2", "w", "Texto w"))),
            )
        )
        assertEquals(listOf("Princípio be"), RoomMethodIndex(repo, atts).findPrinciples("t", 5))
    }

    // ---------- 3.5c: categoria ----------

    @Test
    fun findPrinciples_withIntroductionCategory_passesEnumToRepository() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("be", "be")))
        val repo = MethodFakeTraining(
            mapOf("be" to listOf(methodCandidate(methodPassage("p1", "be", "Princípio"))))
        )
        RoomMethodIndex(repo, atts).findPrinciples("t", 5, category = "introduction")
        assertEquals(TrainingCategory.INTRODUCTION, repo.lastCategory)
    }

    @Test
    fun findPrinciples_withNullCategory_passesUnknownToRepository() = runBlocking {
        val atts = MethodFakeAttachmentDao(listOf(methodAtt("be", "be")))
        val repo = MethodFakeTraining(
            mapOf("be" to listOf(methodCandidate(methodPassage("p1", "be", "Princípio"))))
        )
        RoomMethodIndex(repo, atts).findPrinciples("t", 5)
        // fromSerial(null) → UNKNOWN → retrieveTraining trata como sem boost.
        assertEquals(TrainingCategory.UNKNOWN, repo.lastCategory)
    }
}
