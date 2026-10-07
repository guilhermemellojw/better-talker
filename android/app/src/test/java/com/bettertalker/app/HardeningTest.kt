package com.bettertalker.app

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.HybridRetrieval
import com.bettertalker.app.data.domain.Passage
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.TrainingClassifier
import com.bettertalker.app.data.domain.toPassage
import com.bettertalker.app.data.repo.RoomRetrievalRepository
import com.bettertalker.app.data.repo.RoomTrainingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 10: vazios, extremos, acentos, compatibilidade e mudança de escopo.
 * DAOs falsos em memória; sem Android, sem Room real.
 */
class HardeningTest {

    private class FakePassageDao(var rows: List<PassageEntity> = emptyList()) : PassageDao {
        var scopedCalls = 0
        override suspend fun insertAll(items: List<PassageEntity>) { rows = rows + items }
        override suspend fun forAttachment(attachmentId: String) = rows.filter { it.attachmentId == attachmentId }
        override suspend fun deleteForAttachment(attachmentId: String) { rows = rows.filter { it.attachmentId != attachmentId } }
        override suspend fun searchLike(norm: String, limit: Int) = rows.filter { it.normalized.contains(norm) }.take(limit)
        override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int) =
            rows.filter { it.attachmentId in ids && it.normalized.contains(norm) }.take(limit)
        override suspend fun indexedAttachmentIds() = rows.map { it.attachmentId }.distinct()
        override suspend fun forAttachments(ids: List<String>): List<PassageEntity> {
            scopedCalls++
            return rows.filter { it.attachmentId in ids }.sortedWith(compareBy({ it.attachmentId }, { it.ord }))
        }
        override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> {
            scopedCalls++
            return forAttachments(ids).take(limit)
        }
        override suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity> {
            return rows.filter { it.attachmentId == attachmentId && it.ord in from..to }
                .sortedBy { it.ord }.take(limit)
        }
        override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? {
            return rows.filter { it.attachmentId == attachmentId && it.text.startsWith("# ") && it.ord > ord }
                .minByOrNull { it.ord }
        }
        override suspend fun bySection(attachmentId: String, needle: String, limit: Int): List<PassageEntity> {
            return rows.filter { it.attachmentId == attachmentId && it.section.contains(needle, ignoreCase = true) }
                .sortedBy { it.ord }.take(limit)
        }
        override suspend fun findByRef(ref: String): PassageEntity? =
            rows.firstOrNull { it.ref == ref }
    }

    private class FakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
        override fun observe(): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
        override fun observeForNote(noteId: String): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
        override suspend fun all() = rows
        override suspend fun get(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun upsert(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
        override suspend fun update(a: AttachmentEntity) { rows = rows.filter { it.id != a.id } + a }
        override suspend fun setIndexed(id: String, indexed: Boolean) {}
        override suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?) {}
        override suspend fun setBaseSlot(id: String, slot: String?) {}
        override suspend fun setNote(id: String, noteId: String?) {}
        override suspend fun baseReady(slot: String) = null
        override suspend fun delete(id: String) { rows = rows.filter { it.id != id } }
        override suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?) {}
    }

    private fun passage(id: String, pub: String, text: String, norm: String = text.lowercase()) =
        Passage(id, pub, text, norm, "", "", null, null, 0, SourceType.CONTENT, null, TrainingCategory.UNKNOWN)

    @Test
    fun emptyScopeNeverTouchesDao() = runBlocking {
        val dao = FakePassageDao()
        val atts = FakeAttachmentDao()
        val res = RoomRetrievalRepository(dao, atts)
            .retrieve("oracao", RetrievalScope(emptyList(), emptyList()))
        assertEquals(RetrievalStatus.INSUFFICIENT_SCOPE, res.status)
        assertTrue(res.hits.isEmpty())
        assertEquals(0, dao.scopedCalls)
    }

    @Test
    fun scopeChangeGivesDifferentResults() = runBlocking {
        val dao = FakePassageDao(listOf(
            PassageEntity("p1", "a", "oracao sincera", "oracao sincera"),
            PassageEntity("p2", "b", "oracao sincera", "oracao sincera")
        ))
        val atts = FakeAttachmentDao(listOf(
            AttachmentEntity("a", null, "a.pdf", "pdf", 1, "/x", true, 1L),
            AttachmentEntity("b", null, "b.pdf", "pdf", 1, "/x", true, 1L)
        ))
        val repo = RoomRetrievalRepository(dao, atts)
        val ra = repo.retrieve("oracao", RetrievalScope(listOf("a"), emptyList()))
        val rb = repo.retrieve("oracao", RetrievalScope(listOf("b"), emptyList()))
        assertEquals(listOf("p1"), ra.hits.map { it.passage.id })
        assertEquals(listOf("p2"), rb.hits.map { it.passage.id })
    }

    @Test
    fun extremeQueryIsDeterministicAndBounded() {
        val emoji = "Oração 🙏📖 com FÉ e 'aspas' e <b>html</b> " + "palavra ".repeat(500)
        val cands = listOf(
            passage("p1", "w", "oracao sincera com fe"),
            passage("p2", "w", "texto genérico variado")
        )
        val a = HybridRetrieval.rank(emoji, cands, mapOf("w" to "R"), 5)
        val b = HybridRetrieval.rank(emoji, cands, mapOf("w" to "R"), 5)
        assertEquals(a.map { it.passage.id }, b.map { it.passage.id })
        assertTrue(a.size <= 5)
        assertEquals(a.map { it.passage.id }.toSet().size, a.size)
    }

    @Test
    fun accentsClassifyAndStayStable() {
        assertEquals(TrainingCategory.EXPLANATION, TrainingClassifier.classify(null, null, "Uma explicação clara da oração."))
        assertEquals(TrainingCategory.TRANSITION, TrainingClassifier.classify(null, null, "Use uma transição suave."))
        assertEquals(TrainingCategory.CONCLUSION, TrainingClassifier.classify(null, null, "Termine com uma conclusão breve."))
        assertEquals(TrainingCategory.INTRODUCTION, TrainingClassifier.classify(null, null, "Comece com uma introdução forte."))
    }

    @Test
    fun legacyEntitiesMapToSafeDefaults() {
        // Registro antigo: só os 4-5 campos originais, resto default.
        val old = PassageEntity("p", "a", "texto", "texto")
        val p = old.toPassage()
        assertEquals("", p.ref)
        assertEquals(TrainingCategory.UNKNOWN, p.trainingCategory)
        assertEquals(0, p.order)
        val att = AttachmentEntity("a", null, "w.pdf", "pdf", 1, "/x", true, 1L)
        assertEquals("content", att.sourceType)
    }

    @Test
    fun trainingEmptyScopeIsInsufficient() = runBlocking {
        val dao = FakePassageDao()
        val atts = FakeAttachmentDao()
        val res = RoomTrainingRepository(dao, atts).retrieveTraining(
            "ilustrações", TrainingCategory.ILLUSTRATION, RetrievalScope(listOf("w"), emptyList())
        )
        assertEquals(RetrievalStatus.INSUFFICIENT_SCOPE, res.status)
        assertEquals(0, dao.scopedCalls)
    }
}
