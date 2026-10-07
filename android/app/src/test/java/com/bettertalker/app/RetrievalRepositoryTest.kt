package com.bettertalker.app

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.repo.RoomRetrievalRepository
import com.bettertalker.app.data.repo.RoomTrainingRepository
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.TrainingCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePassageDao(var rows: List<PassageEntity> = emptyList()) : PassageDao {
    var scopedCalls = 0
    override suspend fun insertAll(items: List<PassageEntity>) { rows = rows + items }
    override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
        rows.filter { it.attachmentId == attachmentId }
    override suspend fun deleteForAttachment(attachmentId: String) {
        rows = rows.filter { it.attachmentId != attachmentId }
    }
    override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> =
        rows.filter { it.normalized.contains(norm) }.take(limit)
    override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity> {
        scopedCalls++
        return rows.filter { it.attachmentId in ids && it.normalized.contains(norm) }.take(limit)
    }
    override suspend fun indexedAttachmentIds(): List<String> = rows.map { it.attachmentId }.distinct()
    override suspend fun findByRef(ref: String): PassageEntity? =
        rows.firstOrNull { it.ref == ref }
    override suspend fun forAttachments(ids: List<String>): List<PassageEntity> {
        scopedCalls++
        return rows.filter { it.attachmentId in ids }.sortedWith(compareBy({ it.attachmentId }, { it.ord }))
    }
    override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> =
        forAttachments(ids).take(limit)
    override suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity> =
        rows.filter { it.attachmentId == attachmentId && it.ord in from..to }
            .sortedBy { it.ord }.take(limit)
    override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? =
        rows.filter { it.attachmentId == attachmentId && it.text.startsWith("# ") && it.ord > ord }
            .minByOrNull { it.ord }
}

private class FakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
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

private fun att(id: String, sourceType: String, name: String) =
    AttachmentEntity(id, null, name, "pdf", 1, "/x", true, 1L, sourceType = sourceType)

private fun pass(id: String, att: String, text: String, section: String = "", cat: String? = null) =
    PassageEntity(id, att, text, text.lowercase(), section, trainingCategory = cat)

class RetrievalRepositoryTest {

    private fun setup(): Pair<FakePassageDao, FakeAttachmentDao> {
        val atts = FakeAttachmentDao(listOf(
            att("w", "content", "w.pdf"),
            att("be", "training", "be.pdf"),
            att("outsider", "content", "g.pdf")
        ))
        val dao = FakePassageDao(listOf(
            pass("p1", "w", "Confiar em Jeová ajuda a enfrentar problemas graves."),
            pass("p2", "be", "Use ilustrações simples do cotidiano.", "Ilustrações", "illustration"),
            pass("p3", "outsider", "Confiar em Jeová ajuda a enfrentar problemas graves e grandes.")
        ))
        return dao to atts
    }

    @Test
    fun retrieveLoadsOnlyScopedIds() = runBlocking {
        val (dao, atts) = setup()
        val repo = RoomRetrievalRepository(dao, atts)
        val res = repo.retrieve("confiar jeova problemas", RetrievalScope(listOf("w"), listOf("be")))
        assertEquals(RetrievalStatus.OK, res.status)
        assertTrue(res.hits.isNotEmpty())
        // outsider jamais aparece, mesmo relevante.
        assertTrue(res.hits.none { it.passage.pubId == "outsider" })
        assertTrue(res.hits.all { it.passage.pubId == "w" })
    }

    @Test
    fun emptyScopeNeverHitsDao() = runBlocking {
        val (dao, atts) = setup()
        val repo = RoomRetrievalRepository(dao, atts)
        val res = repo.retrieve("confiar", RetrievalScope(emptyList(), emptyList()))
        assertEquals(RetrievalStatus.INSUFFICIENT_SCOPE, res.status)
        assertTrue(res.hits.isEmpty())
        assertEquals(0, dao.scopedCalls)
    }

    @Test
    fun blankQueryIsInsufficient() = runBlocking {
        val (dao, atts) = setup()
        val res = RoomRetrievalRepository(dao, atts)
            .retrieve("  ", RetrievalScope(listOf("w"), emptyList()))
        assertEquals(RetrievalStatus.INSUFFICIENT_SCOPE, res.status)
    }

    @Test
    fun trainingTrackIsolatesBeTh() = runBlocking {
        val (dao, atts) = setup()
        val repo = RoomTrainingRepository(dao, atts)
        val res = repo.retrieveTraining(
            "ilustrações simples", TrainingCategory.ILLUSTRATION,
            RetrievalScope(listOf("w"), listOf("be"))
        )
        assertEquals(RetrievalStatus.OK, res.status)
        assertTrue(res.hits.isNotEmpty())
        assertTrue(res.hits.all { it.passage.pubId == "be" })
        assertEquals("p2", res.hits.first().passage.id)
    }

    @Test
    fun contentTrackNeverReturnsTraining() = runBlocking {
        val (dao, atts) = setup()
        val res = RoomRetrievalRepository(dao, atts)
            .retrieve("ilustrações simples", RetrievalScope(listOf("w"), listOf("be")))
        assertTrue(res.hits.none { it.passage.pubId == "be" })
    }

    // ---------- F20: corpus grande (it/rsg com 100k+ trechos) ----------

    @Test
    fun retrieveCobreCorpusGrandeAlemDoInicio() = runBlocking {
        // 3.000 trechos neutros + 1 relevante no FIM (fora do antigo
        // take(2000) por (attachmentId, ord)) — a busca por termo acha.
        val rows = (1..3000).map { pass("n$it", "w", "texto neutro numero $it") } +
            pass("alvo", "w", "Zafenate-Paneia serviu como governador do Egito.")
        val dao = FakePassageDao(rows)
        val atts = FakeAttachmentDao(listOf(att("w", "content", "it_T.jwpub")))
        val res = RoomRetrievalRepository(dao, atts)
            .retrieve("Zafenate-Paneia", RetrievalScope(listOf("w"), emptyList()))
        assertEquals(RetrievalStatus.OK, res.status)
        assertEquals("alvo", res.hits.first().passage.id)
    }

    @Test
    fun retrieveSemMatchCaiNoTetoDoSqlSemEstourar() = runBlocking {
        // Query sem nenhum match: fallback limitado no SQL (não materializa tudo).
        val rows = (1..3000).map { pass("n$it", "w", "texto neutro numero $it") }
        val dao = FakePassageDao(rows)
        val atts = FakeAttachmentDao(listOf(att("w", "content", "it_T.jwpub")))
        val res = RoomRetrievalRepository(dao, atts)
            .retrieve("xyzabc quux", RetrievalScope(listOf("w"), emptyList()))
        assertEquals(RetrievalStatus.OK, res.status)
        assertTrue(res.hits.isEmpty())
    }
}
