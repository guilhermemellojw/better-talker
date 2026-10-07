package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.LlmResponseMeta
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class IntFakePassageDao(var rows: List<PassageEntity> = emptyList()) : PassageDao {
    override suspend fun insertAll(items: List<PassageEntity>) { rows = rows + items }
    override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
        rows.filter { it.attachmentId == attachmentId }
    override suspend fun deleteForAttachment(attachmentId: String) {
        rows = rows.filter { it.attachmentId != attachmentId }
    }
    override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> =
        rows.filter { it.normalized.contains(norm) }.take(limit)
    override suspend fun searchLikeIn(ids: List<String>, norm: String, limit: Int): List<PassageEntity> {
        return rows.filter { it.attachmentId in ids && it.normalized.contains(norm) }.take(limit)
    }
    override suspend fun indexedAttachmentIds(): List<String> = rows.map { it.attachmentId }.distinct()
    override suspend fun findByRef(ref: String): PassageEntity? =
        rows.firstOrNull { it.ref == ref }
    override suspend fun forAttachments(ids: List<String>): List<PassageEntity> {
        return rows.filter { it.attachmentId in ids }.sortedWith(compareBy({ it.attachmentId }, { it.ord }))
    }
    override suspend fun forAttachmentsLimited(ids: List<String>, limit: Int): List<PassageEntity> {
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
}

private class IntFakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
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

private class IntFakeLlm(private val behavior: (LlmRequest) -> LlmResponse) : LlmProvider {
    override val id: String = "fake"
    override val model: String = "fake-model"
    override suspend fun generate(request: LlmRequest): LlmResponse = behavior(request)
}

private class IntThrowingLlm(private val error: Throwable) : LlmProvider {
    override val id: String = "fake"
    override val model: String = "fake-model"
    override suspend fun generate(request: LlmRequest): LlmResponse = throw error
}

class OutlineProposerIntegrationTest {

    private fun nwtAttachment() = AttachmentEntity(
        id = "nwt", noteId = null, fileName = "nwt.pdf", kind = "pdf", sizeBytes = 1,
        appPath = "/x", indexed = true, addedAt = 1L, baseSlot = "nwt",
    )

    private fun nwtPassages() = listOf(
        PassageEntity("nwt-p0", "nwt", "A ressurreição dos mortos é certa", "a ressurreicao dos mortos e certa",
            "João", "João 5:28", ord = 0),
        PassageEntity("nwt-p1", "nwt", "Todos os que estão na ressurreição", "todos os que estao na ressurreicao",
            "Atos", "Atos 24:15", ord = 1),
    )

    private fun okResponse(text: String) = LlmResponse(
        text = text,
        meta = LlmResponseMeta("fake", "fake-model", 10L, 1, offline = false),
    )

    private fun validJson(vararg refs: String = arrayOf("João 5:28")): String {
        val refsJson = refs.joinToString(",") { "\"$it\"" }
        return """{"title":"A esperança da ressurreição","summary":"Esboço sobre a ressurreição.",
            "sections":[
            {"title":"INTRODUÇÃO","minutes":3,"mainIdea":"Gancho sobre a finitude","bibleRefs":[$refsJson]},
            {"title":"DESENVOLVIMENTO","minutes":7,"mainIdea":"A promessa bíblica","bibleRefs":[$refsJson]},
            {"title":"CONCLUSÃO","minutes":5,"mainIdea":"Confiar na promessa","bibleRefs":[$refsJson]}]}"""
    }

    @Test
    fun propose_endToEnd_withBibleAttachments_returnsThreeProposals() = runBlocking {
        val passages = IntFakePassageDao(nwtPassages())
        val attachments = IntFakeAttachmentDao(listOf(nwtAttachment()))
        val llm = IntFakeLlm { okResponse(validJson()) }
        val proposer = OutlineProposerFactory(passages, attachments, llm).create()
        val result = proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertEquals(3, result.size)
        assertEquals(
            listOf(OutlineAngle.DOCTRINAL, OutlineAngle.PRACTICAL, OutlineAngle.NARRATIVE),
            result.map { it.angle },
        )
    }

    @Test
    fun propose_withEmptyRetrieval_returnsEmpty() = runBlocking {
        val passages = IntFakePassageDao()
        val attachments = IntFakeAttachmentDao()
        val llm = IntFakeLlm { okResponse(validJson()) }
        val proposer = OutlineProposerFactory(passages, attachments, llm).create()
        assertTrue(proposer.propose("ressurreição", 15, Audience.GENERAL).isEmpty())
    }

    @Test
    fun propose_withLlmReturningInvalidJson_returnsEmpty() = runBlocking {
        val passages = IntFakePassageDao(nwtPassages())
        val attachments = IntFakeAttachmentDao(listOf(nwtAttachment()))
        val llm = IntFakeLlm { okResponse("garbage, não é JSON") }
        val proposer = OutlineProposerFactory(passages, attachments, llm).create()
        assertTrue(proposer.propose("ressurreição", 15, Audience.GENERAL).isEmpty())
    }

    @Test
    fun propose_withLlmThrowingGenericException_returnsEmpty() = runBlocking {
        val passages = IntFakePassageDao(nwtPassages())
        val attachments = IntFakeAttachmentDao(listOf(nwtAttachment()))
        val llm = IntThrowingLlm(RuntimeException("boom"))
        val proposer = OutlineProposerFactory(passages, attachments, llm).create()
        assertTrue(proposer.propose("ressurreição", 15, Audience.GENERAL).isEmpty())
    }

    @Test
    fun propose_withLlmThrowingCancellation_propagates() = runBlocking {
        val passages = IntFakePassageDao(nwtPassages())
        val attachments = IntFakeAttachmentDao(listOf(nwtAttachment()))
        val llm = IntThrowingLlm(CancellationException("cancelled"))
        val proposer = OutlineProposerFactory(passages, attachments, llm).create()
        try {
            proposer.propose("ressurreição", 15, Audience.GENERAL)
            fail("Esperava CancellationException")
        } catch (_: CancellationException) {
            // esperado — cancelamento atravessa proposer + generator sem ser engolido
        }
    }

    @Test
    fun propose_withLlmReturningInventedBibleRefs_returnsEmpty() = runBlocking {
        val passages = IntFakePassageDao(nwtPassages())
        val attachments = IntFakeAttachmentDao(listOf(nwtAttachment()))
        val llm = IntFakeLlm { okResponse(validJson("Gên 99:99")) }
        val proposer = OutlineProposerFactory(passages, attachments, llm).create()
        val result = proposer.propose("ressurreição", 15, Audience.GENERAL)
        assertTrue(result.isEmpty())
    }
}
