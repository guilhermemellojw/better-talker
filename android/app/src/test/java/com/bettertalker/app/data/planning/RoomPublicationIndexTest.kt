package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.repo.RetrievalRepository
import com.bettertalker.app.data.domain.RetrievalResult
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.domain.planning.PublicationRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PubFakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
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

private class PubFakeRetrieval(
    private val byAttachment: Map<String, List<RetrievalCandidate>> = emptyMap(),
) : RetrievalRepository {
    var calls = 0
    var lastScope: RetrievalScope? = null
    override suspend fun retrieve(query: String, scope: RetrievalScope, limit: Int): RetrievalResult {
        calls++
        lastScope = scope
        val hits = scope.contentSourceIds.flatMap { byAttachment[it].orEmpty() }
        if (hits.isEmpty()) return RetrievalResult(RetrievalStatus.EMPTY_CORPUS, emptyList())
        return RetrievalResult(RetrievalStatus.OK, hits)
    }
}

private fun pubAtt(id: String, slot: String?, indexed: Boolean = true) = AttachmentEntity(
    id = id, noteId = null, fileName = "$id.pdf", kind = "pdf", sizeBytes = 1,
    appPath = "/x", indexed = indexed, addedAt = 1L, baseSlot = slot,
)

private fun pubPassage(
    id: String,
    pub: String,
    symbol: String?,
    page: Int? = null,
    paragraph: Int? = null,
) = Passage(
    id = id, pubId = pub, text = "texto $id", normalizedText = "texto $id", ref = "ref $id",
    section = "", page = page, paragraph = paragraph, order = 0,
    sourceType = SourceType.CONTENT, symbol = symbol, trainingCategory = TrainingCategory.UNKNOWN,
)

private fun pubCandidate(passage: Passage) = RetrievalCandidate(
    passage = passage, publicationTitle = "w", lexicalScore = 0.5,
    metadataScore = 0.5, finalScore = 0.5, matchedTerms = emptyList(), foundBy = listOf("lexical"),
)

class RoomPublicationIndexTest {

    @Test
    fun noIndexedContentAttachments_returnsEmpty() = runBlocking {
        val atts = PubFakeAttachmentDao(listOf(pubAtt("w", null, indexed = false), pubAtt("be", "be")))
        val repo = PubFakeRetrieval()
        assertTrue(RoomPublicationIndex(repo, atts).findByTheme("fé", 5).isEmpty())
        assertEquals(0, repo.calls)
    }

    @Test
    fun indexedContentAttachment_returnsPublicationRefs() = runBlocking {
        val atts = PubFakeAttachmentDao(listOf(pubAtt("w", null)))
        val repo = PubFakeRetrieval(
            mapOf("w" to listOf(
                pubCandidate(pubPassage("p1", "w", "w19.03", page = 8, paragraph = 2)),
            ))
        )
        assertEquals(
            listOf(PublicationRef("w19.03", page = 8, paragraph = 2)),
            RoomPublicationIndex(repo, atts).findByTheme("fé", 5),
        )
        assertEquals(listOf("w"), repo.lastScope?.contentSourceIds)
    }

    @Test
    fun duplicateTuples_areDeduplicated() = runBlocking {
        val atts = PubFakeAttachmentDao(listOf(pubAtt("w", null)))
        val repo = PubFakeRetrieval(
            mapOf("w" to listOf(
                pubCandidate(pubPassage("p1", "w", "w19.03", page = 8)),
                pubCandidate(pubPassage("p2", "w", "w19.03", page = 8)),
                pubCandidate(pubPassage("p3", "w", "w19.03", page = 9)),
            ))
        )
        assertEquals(
            listOf(PublicationRef("w19.03", page = 8), PublicationRef("w19.03", page = 9)),
            RoomPublicationIndex(repo, atts).findByTheme("fé", 5),
        )
    }

    @Test
    fun blankSymbols_areFiltered() = runBlocking {
        val atts = PubFakeAttachmentDao(listOf(pubAtt("w", null)))
        val repo = PubFakeRetrieval(
            mapOf("w" to listOf(
                pubCandidate(pubPassage("p1", "w", "  ")),
                pubCandidate(pubPassage("p2", "w", null)),
                pubCandidate(pubPassage("p3", "w", "w19.03")),
            ))
        )
        assertEquals(
            listOf(PublicationRef("w19.03")),
            RoomPublicationIndex(repo, atts).findByTheme("fé", 5),
        )
    }

    @Test
    fun limitIsRespected() = runBlocking {
        val atts = PubFakeAttachmentDao(listOf(pubAtt("w", null)))
        val repo = PubFakeRetrieval(
            mapOf("w" to listOf(
                pubCandidate(pubPassage("p1", "w", "a")),
                pubCandidate(pubPassage("p2", "w", "b")),
                pubCandidate(pubPassage("p3", "w", "c")),
            ))
        )
        assertEquals(
            listOf(PublicationRef("a"), PublicationRef("b")),
            RoomPublicationIndex(repo, atts).findByTheme("fé", 2),
        )
    }

    @Test
    fun bibleAndTrainingAttachments_areIgnored() = runBlocking {
        val atts = PubFakeAttachmentDao(listOf(pubAtt("w", null), pubAtt("nwt", "nwt"), pubAtt("be", "be")))
        val repo = PubFakeRetrieval(
            mapOf(
                "w" to listOf(pubCandidate(pubPassage("p1", "w", "w19.03"))),
                "nwt" to listOf(pubCandidate(pubPassage("p2", "nwt", "nwt"))),
                "be" to listOf(pubCandidate(pubPassage("p3", "be", "be"))),
            )
        )
        assertEquals(
            listOf(PublicationRef("w19.03")),
            RoomPublicationIndex(repo, atts).findByTheme("fé", 5),
        )
    }
}
