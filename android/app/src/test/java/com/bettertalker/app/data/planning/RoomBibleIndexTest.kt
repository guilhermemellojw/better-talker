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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class BibleFakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
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

private class BibleFakeRetrieval(
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

private fun bibleAtt(id: String, slot: String?, indexed: Boolean = true) = AttachmentEntity(
    id = id, noteId = null, fileName = "$id.pdf", kind = "pdf", sizeBytes = 1,
    appPath = "/x", indexed = indexed, addedAt = 1L, baseSlot = slot,
)

private fun biblePassage(id: String, pub: String, ref: String, text: String = "texto $id") = Passage(
    id = id, pubId = pub, text = text, normalizedText = text.lowercase(), ref = ref,
    section = "", page = null, paragraph = null, order = 0,
    sourceType = SourceType.BIBLE, symbol = "nwt", trainingCategory = TrainingCategory.UNKNOWN,
)

private fun bibleCandidate(passage: Passage) = RetrievalCandidate(
    passage = passage, publicationTitle = "nwt", lexicalScore = 0.5,
    metadataScore = 0.5, finalScore = 0.5, matchedTerms = emptyList(), foundBy = listOf("lexical"),
)

class RoomBibleIndexTest {

    @Test
    fun noIndexedBibleAttachments_returnsEmpty() = runBlocking {
        val atts = BibleFakeAttachmentDao(listOf(bibleAtt("w", null), bibleAtt("nwt", "nwt", indexed = false)))
        val repo = BibleFakeRetrieval()
        assertTrue(RoomBibleIndex(repo, atts).findByTheme("ressurreição", 5).isEmpty())
        assertEquals(0, repo.calls)
    }

    @Test
    fun indexedBibleAttachment_withMatchingPassages_returnsRefs() = runBlocking {
        val atts = BibleFakeAttachmentDao(listOf(bibleAtt("nwt", "nwt")))
        val repo = BibleFakeRetrieval(
            mapOf("nwt" to listOf(
                bibleCandidate(biblePassage("p1", "nwt", "João 5:28")),
                bibleCandidate(biblePassage("p2", "nwt", "Atos 24:15")),
            ))
        )
        val refs = RoomBibleIndex(repo, atts).findByTheme("ressurreição", 5)
        assertEquals(listOf("João 5:28", "Atos 24:15"), refs)
        assertEquals(listOf("nwt"), repo.lastScope?.contentSourceIds)
    }

    @Test
    fun duplicateRefs_areDeduplicated() = runBlocking {
        val atts = BibleFakeAttachmentDao(listOf(bibleAtt("nwt", "nwt")))
        val repo = BibleFakeRetrieval(
            mapOf("nwt" to listOf(
                bibleCandidate(biblePassage("p1", "nwt", "João 5:28")),
                bibleCandidate(biblePassage("p2", "nwt", "João 5:28")),
            ))
        )
        assertEquals(listOf("João 5:28"), RoomBibleIndex(repo, atts).findByTheme("t", 5))
    }

    @Test
    fun blankRefs_areFiltered() = runBlocking {
        val atts = BibleFakeAttachmentDao(listOf(bibleAtt("nwt", "nwt")))
        val repo = BibleFakeRetrieval(
            mapOf("nwt" to listOf(
                bibleCandidate(biblePassage("p1", "nwt", "  ")),
                bibleCandidate(biblePassage("p2", "nwt", "Apo 21:4")),
            ))
        )
        assertEquals(listOf("Apo 21:4"), RoomBibleIndex(repo, atts).findByTheme("t", 5))
    }

    @Test
    fun limitIsRespected() = runBlocking {
        val atts = BibleFakeAttachmentDao(listOf(bibleAtt("nwt", "nwt")))
        val repo = BibleFakeRetrieval(
            mapOf("nwt" to listOf(
                bibleCandidate(biblePassage("p1", "nwt", "A")),
                bibleCandidate(biblePassage("p2", "nwt", "B")),
                bibleCandidate(biblePassage("p3", "nwt", "C")),
            ))
        )
        assertEquals(listOf("A", "B"), RoomBibleIndex(repo, atts).findByTheme("t", 2))
    }

    @Test
    fun nonBibleAttachments_areIgnored() = runBlocking {
        val atts = BibleFakeAttachmentDao(listOf(bibleAtt("nwt", "nwt"), bibleAtt("w", null), bibleAtt("be", "be")))
        val repo = BibleFakeRetrieval(
            mapOf(
                "nwt" to listOf(bibleCandidate(biblePassage("p1", "nwt", "João 5:28"))),
                "w" to listOf(bibleCandidate(biblePassage("p2", "w", "w 1:1"))),
                "be" to listOf(bibleCandidate(biblePassage("p3", "be", "be 1:1"))),
            )
        )
        assertEquals(listOf("João 5:28"), RoomBibleIndex(repo, atts).findByTheme("t", 5))
    }
}
