package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 3.5a: testes do [RoomReferenceResolver] com fakes in-memory
 * (padrão do projeto; sem Android/Room).
 */
class RoomReferenceResolverTest {

    private class FakePassageDao(var rows: List<PassageEntity> = emptyList()) : PassageDao {
        override suspend fun insertAll(items: List<PassageEntity>) { rows = rows + items }
        override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId }
        override suspend fun deleteForAttachment(attachmentId: String) {
            rows = rows.filter { it.attachmentId != attachmentId }
        }
        override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.normalized.contains(norm) }.take(limit)
        override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId in ids && it.normalized.contains(norm) }.take(limit)
        override suspend fun indexedAttachmentIds(): List<String> =
            rows.map { it.attachmentId }.distinct()
        override suspend fun findByRef(ref: String): PassageEntity? =
            rows.firstOrNull { it.ref == ref }
        override suspend fun forAttachments(ids: List<String>): List<PassageEntity> =
            rows.filter { it.attachmentId in ids }.sortedWith(compareBy({ it.attachmentId }, { it.ord }))
        override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> =
            forAttachments(ids).take(limit)
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

    private fun passage(id: String, att: String, ref: String, text: String, normalized: String) =
        PassageEntity(id, att, text, normalized, section = "", ref = ref, ord = 0)

    private fun attachment(id: String, symbol: String?, indexed: Boolean = true) =
        AttachmentEntity(id, null, "$id.pdf", "pdf", 1L, "/x", indexed, 1L, baseSlot = symbol, symbol = symbol)

    private fun resolver(
        passages: List<PassageEntity> = emptyList(),
        attachments: List<AttachmentEntity> = emptyList(),
    ) = RoomReferenceResolver(FakePassageDao(passages), FakeAttachmentDao(attachments))

    // ---------- Bible: RESOLVED ----------

    @Test
    fun resolveBible_tnmExact_returnsResolved() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Gên 3:6", "texto de gen 3 6", "texto de gen 3 6")))
        val out = r.resolveBible("Gên 3:6")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
        assertEquals("texto de gen 3 6", out.text)
        assertEquals("p1", out.passageId)
    }

    @Test
    fun resolveBible_fullName_normalizesAndFinds() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Gên 3:6", "texto", "texto")))
        val out = r.resolveBible("Gênesis 3:6")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
    }

    @Test
    fun resolveBible_oldAbbreviation_normalizesAndFinds() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Gên 3:6", "texto", "texto")))
        val out = r.resolveBible("Gê 3:6")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
    }

    @Test
    fun resolveBible_withLetterSuffix_works() = runBlocking {
        val r = resolver(listOf(passage("p1", "nwt", "Ap 15:3", "texto ap", "texto ap")))
        val out = r.resolveBible("Re 15:3b")
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Ap 15:3", out.canonicalRef)
    }

    // ---------- Bible: UNRESOLVED / MISSING_CORPUS ----------

    @Test
    fun resolveBible_notInCorpus_returnsUnresolved() = runBlocking {
        val out = resolver().resolveBible("Gên 3:6")
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertEquals("Gên 3:6", out.canonicalRef)
        assertNull(out.text)
        assertNull(out.passageId)
    }

    @Test
    fun resolveBible_invalidFormat_returnsMissingCorpus() = runBlocking {
        val out = resolver().resolveBible("XYZ 3:6")
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
        assertNull(out.canonicalRef)
    }

    // ---------- Publication: PARTIAL / MISSING_CORPUS / UNRESOLVED ----------

    @Test
    fun resolvePublication_symbolFound_returnsPartial() = runBlocking {
        val atts = listOf(attachment("w1", "w21.08"))
        val pass = listOf(passage("p1", "w1", "w21.08 §13", "texto do paragrafo 13", "texto do paragrafo 13 da revista"))
        val out = resolver(pass, atts).resolvePublication(PublicationRef("w21.08", 18, 13))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertEquals("texto do paragrafo 13", out.text)
        assertEquals("p1", out.passageId)
    }

    @Test
    fun resolvePublication_noAttachmentForSymbol_returnsMissingCorpus() = runBlocking {
        val out = resolver().resolvePublication(PublicationRef("w21.08", 18, 13))
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_symbolFoundButNoText_returnsUnresolved() = runBlocking {
        val atts = listOf(attachment("w1", "w21.08"))
        val pass = listOf(passage("p1", "w1", "outro", "nada a ver aqui", "nada a ver aqui"))
        val out = resolver(pass, atts).resolvePublication(PublicationRef("w21.08", 18, 13))
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
    }

    @Test
    fun resolvePublication_symbolWithSpace_works() = runBlocking {
        val atts = listOf(attachment("g1", "g 8/13"))
        val pass = listOf(passage("p1", "g1", "g 8/13", "trecho da despertai", "trecho da despertai g 8 13"))
        val out = resolver(pass, atts).resolvePublication(PublicationRef("g 8/13"))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertEquals("p1", out.passageId)
    }

    @Test
    fun resolvePublication_oldFormatSymbol_works() = runBlocking {
        val atts = listOf(attachment("w1", "w94 1/8"))
        val pass = listOf(passage("p1", "w1", "w94 1/8", "trecho antigo", "trecho antigo w94 1 8"))
        val out = resolver(pass, atts).resolvePublication(PublicationRef("w94 1/8"))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
    }

    @Test
    fun resolvePublication_paragraphNull_stillResolves() = runBlocking {
        val atts = listOf(attachment("w1", "w21.08"))
        val pass = listOf(passage("p1", "w1", "w21.08", "texto da pag 18", "texto da pag 18"))
        val out = resolver(pass, atts).resolvePublication(PublicationRef("w21.08", 18, null))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertEquals("p1", out.passageId)
    }
}
