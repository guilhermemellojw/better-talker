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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        override suspend fun betweenOrd(attachmentId: String, from: Int, to: Int, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.ord in from..to }
                .sortedBy { it.ord }.take(limit)
        override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? =
            rows.filter { it.attachmentId == attachmentId && it.text.startsWith("# ") && it.ord > ord }
                .minByOrNull { it.ord }
        override suspend fun bySection(attachmentId: String, needle: String, limit: Int): List<PassageEntity> =
            rows.filter { it.attachmentId == attachmentId && it.section.contains(needle, ignoreCase = true) }
                .sortedBy { it.ord }.take(limit)
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

    private fun passage(
        id: String,
        att: String,
        ref: String,
        text: String,
        normalized: String,
        section: String = "",
        ord: Int = 0,
        paragraph: Int? = null,
    ) = PassageEntity(
        id, att, text, normalized, section = section, ref = ref,
        paragraph = paragraph, ord = ord,
    )

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

    // ---------- Publication: T3 (artigo/lição + parágrafo) ----------

    private fun itAtt() = attachment("it", null)
        .copy(fileName = "it_T.jwpub", symbol = null, baseSlot = null)

    private fun itRows() = listOf(
        passage("t1", "it", "# Gedalias", "# Gedalias", "gedalias", ord = 10),
        passage("t2", "it", "GEDALIAS", "GEDALIAS", "gedalias", ord = 11),
        passage("p1", "it", "it §1", "Cantor levita que serviu no templo.", "cantor levita", ord = 12, paragraph = 1),
        passage("p4", "it", "it §4", "Filho de Aicão, filho de Safã.", "filho de aicao", ord = 15, paragraph = 4),
        passage("t3", "it", "# Gederotaim", "# Gederotaim", "gederotaim", ord = 20),
        passage("x1", "it", "it §1", "outro verbete qualquer", "outro verbete qualquer", ord = 21),
    )

    @Test
    fun resolvePublication_artigoComParagrafo_returnsResolved() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertEquals("Filho de Aicão, filho de Safã.", out.text)
        assertEquals("it “Gedalias” §4", out.canonicalRef)
        assertEquals("p4", out.passageId)
    }

    @Test
    fun resolvePublication_artigoSemParagrafoIndexado_returnsPartial() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", article = "Gedalias"))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertTrue(out.text!!.contains("Cantor levita"))
        // A fatia termina no próximo título ("# Gederotaim").
        assertFalse(out.text!!.contains("outro verbete"))
    }

    @Test
    fun resolvePublication_it1_casaArquivoUnificado() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it-1", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
    }

    @Test
    fun resolvePublication_licao_resolvePorTitulo() = runBlocking {
        val atts = listOf(
            attachment("lmd", null).copy(fileName = "lmd_T.jwpub", symbol = null, baseSlot = null)
        )
        val rows = listOf(
            passage("l1", "lmd", "# Lição 3: Como estudar", "# Lição 3: Como estudar", "licao 3 como estudar", ord = 0),
            passage("l2", "lmd", "lmd §1", "Texto da lição três.", "texto da licao tres", ord = 1, paragraph = 1),
            passage("l3", "lmd", "# Lição 4: Outra", "# Lição 4: Outra", "licao 4 outra", ord = 2),
        )
        val out = resolver(rows, atts)
            .resolvePublication(PublicationRef("lmd", chapter = "lição 3", paragraph = 1))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertTrue(out.text!!.contains("Texto da lição três"))
        assertFalse(out.text!!.contains("Lição 4"))
    }

    @Test
    fun resolvePublication_licao_resolvePorSecao() = runBlocking {
        val atts = listOf(
            attachment("lmd", null).copy(fileName = "lmd_T.jwpub", symbol = null, baseSlot = null)
        )
        val rows = listOf(
            passage("l1", "lmd", "lmd §1", "Texto da lição três.", "texto da licao tres", section = "Lição 3", ord = 0, paragraph = 1),
            passage("l2", "lmd", "lmd §2", "Mais da lição três.", "mais da licao tres", section = "Lição 3", ord = 1, paragraph = 2),
            passage("l3", "lmd", "lmd §1", "Outra lição.", "outra licao", section = "Lição 4", ord = 2, paragraph = 1),
        )
        val out = resolver(rows, atts)
            .resolvePublication(PublicationRef("lmd", chapter = "lição 3", paragraph = 1))
        assertEquals(ReferenceStatus.RESOLVED, out.status)
        assertTrue(out.text!!.contains("Texto da lição três"))
        assertFalse(out.text!!.contains("Outra lição"))
    }

    @Test
    fun resolvePublication_capituloAbreviado_casaComPalavraCheia() = runBlocking {
        // "cap. 5" deve casar com a seção "Capítulo 5" (variante T4).
        val atts = listOf(
            attachment("rr", null).copy(fileName = "rr_T.jwpub", symbol = null, baseSlot = null)
        )
        val rows = listOf(
            passage("r1", "rr", "rr §1", "Texto do capítulo cinco.", "texto do capitulo cinco", section = "Capítulo 5", ord = 0),
        )
        val out = resolver(rows, atts)
            .resolvePublication(PublicationRef("rr", chapter = "cap. 5"))
        assertEquals(ReferenceStatus.PARTIAL, out.status)
        assertTrue(out.text!!.contains("Texto do capítulo cinco"))
    }

    @Test
    fun resolvePublication_artigoInexistente_returnsUnresolved() = runBlocking {
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", article = "NaoExiste"))
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_soPagina_semUnidade_returnsUnresolved() = runBlocking {
        // "it 813" (só página): sem unidade identificável — nunca trecho aleatório.
        val out = resolver(itRows(), listOf(itAtt()))
            .resolvePublication(PublicationRef("it", page = 813))
        assertEquals(ReferenceStatus.UNRESOLVED, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_semAnexo_returnsMissingCorpus() = runBlocking {
        val out = resolver()
            .resolvePublication(PublicationRef("it", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
        assertNull(out.text)
    }

    @Test
    fun resolvePublication_anexoNaoIndexado_returnsMissingCorpus() = runBlocking {
        // Arquivo presente mas não indexado = sem corpus pesquisável.
        val out = resolver(itRows(), listOf(itAtt().copy(indexed = false)))
            .resolvePublication(PublicationRef("it", article = "Gedalias", paragraph = 4))
        assertEquals(ReferenceStatus.MISSING_CORPUS, out.status)
    }
}
