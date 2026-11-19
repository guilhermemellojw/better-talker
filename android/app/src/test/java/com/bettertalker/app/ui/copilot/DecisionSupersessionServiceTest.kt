package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.DecisionSubject
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.SupersessionConflictReason
import com.bettertalker.app.data.copilot.SupersessionResult
import com.bettertalker.app.data.db.DecisionDao
import com.bettertalker.app.data.db.DecisionEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ServiceFakeDao : DecisionDao {
    var rows: List<DecisionEntity> = emptyList()
    var insertCalls = 0
    var upsertAllCalls = 0
    var deleteCalls = 0
    var linkCalls = 0

    override suspend fun forNote(noteId: String): List<DecisionEntity> =
        rows.filter { it.noteId == noteId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun get(id: String): DecisionEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun upsert(record: DecisionEntity) {
        rows = rows.filter { it.id != record.id } + record
    }

    override suspend fun upsertAll(records: List<DecisionEntity>) {
        upsertAllCalls++
        records.forEach { upsert(it) }
    }

    override suspend fun insertIfAbsent(record: DecisionEntity): Long {
        insertCalls++
        if (rows.any { it.id == record.id }) return -1
        rows = rows + record
        return 1
    }

    override suspend fun markSupersededIfActive(id: String, updatedAt: Long): Int {
        val existing = rows.firstOrNull { it.id == id } ?: return 0
        if (existing.decisionState != DecisionState.ACTIVE.name) return 0
        rows = rows.map {
            if (it.id == id) {
                it.copy(decisionState = DecisionState.SUPERSEDED.name, updatedAt = updatedAt)
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun linkReplacementIfUnlinked(
        replacementId: String,
        previousId: String,
        expectedUpdatedAt: Long,
        updatedAt: Long,
    ): Int {
        linkCalls++
        val existing = rows.firstOrNull { it.id == replacementId } ?: return 0
        if (
            existing.supersedesId != null ||
            existing.decisionState != DecisionState.ACTIVE.name ||
            existing.updatedAt != expectedUpdatedAt
        ) {
            return 0
        }
        rows = rows.map {
            if (it.id == replacementId) {
                it.copy(supersedesId = previousId, updatedAt = updatedAt)
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun deleteById(id: String) {
        deleteCalls++
        rows = rows.filter { it.id != id }
    }

    override suspend fun deleteForNote(noteId: String) {
        deleteCalls++
        rows = rows.filter { it.noteId != noteId }
    }

    override suspend fun countForNote(noteId: String): Int =
        rows.count { it.noteId == noteId }
}

private class ServiceFakeTransactionRunner : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

/** 1C.2.1B2C — fluxo de consulta, confirmação e atualização da pergunta. */
class DecisionSupersessionServiceTest {

    private lateinit var dao: ServiceFakeDao
    private lateinit var service: DecisionSupersessionService

    @Before
    fun setUp() {
        dao = ServiceFakeDao()
        service = DecisionSupersessionService(
            DecisionMemoryRepository(dao, ServiceFakeTransactionRunner()),
        )
    }

    private fun decision(
        id: String,
        state: DecisionState = DecisionState.ACTIVE,
        text: String = "decisão $id",
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
        subjectKey: String? = DecisionSubject.SECTION_APPROACH.key,
    ): DecisionRecord = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = state,
        sectionLink = SectionLink.VALID,
        applicationState = ApplicationState.NOT_APPLIED,
        text = text,
        source = DecisionSource.STRUCTURE_ACCEPT,
        sectionId = "s1",
        originalSectionId = "s1",
        sectionTitleSnapshot = "Ponto 1",
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = 1,
        sectionContentHashSnapshot = "hash-s1",
        originalSectionTitleSnapshot = "Ponto 1",
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = 1,
        originalSectionContentHashSnapshot = "hash-s1",
        subjectKey = subjectKey,
        sourceProposalId = "prop-$id",
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
    )

    private suspend fun seed(vararg decisions: DecisionRecord) {
        decisions.forEach { dao.upsert(it.toEntity()) }
    }

    @Test
    fun semAnteriorCompativelNaoHaPergunta() = runBlocking {
        val replacement = decision("b")
        seed(replacement)

        assertNull(service.findPrompt(replacement))
    }

    @Test
    fun anteriorCompativelGeraPerguntaComTimestampsAtuais() = runBlocking {
        val previous = decision("a", updatedAt = 15L)
        val replacement = decision("b", createdAt = 20L)
        seed(previous, replacement)

        val prompt = service.findPrompt(replacement)

        assertNotNull(prompt)
        assertEquals("a", prompt!!.previous.id)
        assertEquals("b", prompt.replacement.id)
        assertEquals(15L, prompt.toConfirmation(now = 30L).expectedPreviousUpdatedAt)
    }

    @Test
    fun confirmacaoVinculaAsDuasDecisoesPersistidas() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", createdAt = 20L, updatedAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!

        val result = service.confirm(prompt, now = 30L)

        assertTrue(result is SupersessionResult.Committed)
        assertEquals(DecisionState.SUPERSEDED.name, dao.get("a")!!.decisionState)
        assertEquals("a", dao.get("b")!!.supersedesId)
        // A B2C não reaplica estrutura, não insere de novo, não apaga e não
        // usa upsertAll() para contornar a colisão.
        assertEquals(0, dao.insertCalls)
        assertEquals(0, dao.upsertAllCalls)
        assertEquals(0, dao.deleteCalls)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun confirmacaoRepetidaNaoRepeteEscrita() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", createdAt = 20L, updatedAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!
        service.confirm(prompt, now = 30L)
        val before = dao.rows

        val repeated = service.confirm(prompt, now = 40L)

        assertTrue(repeated is SupersessionResult.AlreadyApplied)
        assertEquals(before, dao.rows)
    }

    @Test
    fun perguntaUsaORetratoPersistidoDaSubstituta() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", text = "texto persistido", createdAt = 20L)
        seed(previous, replacement)
        val copiaEmMemoria = replacement.copy(text = "texto em memória")

        val prompt = service.findPrompt(copiaEmMemoria)!!

        assertEquals("texto persistido", prompt.replacement.text)
        assertEquals(20L, prompt.replacement.updatedAt)
    }

    @Test
    fun substitutaAlteradaDepoisDaPerguntaNaoVincula() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", text = "texto exibido", createdAt = 20L, updatedAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!
        dao.upsert(
            replacement.copy(text = "texto novo", updatedAt = 21L).toEntity(),
        )
        val before = dao.rows

        val result = service.confirm(prompt, now = 30L)

        assertTrue(result is SupersessionResult.ReplacementStale)
        result as SupersessionResult.ReplacementStale
        assertEquals("b", result.replacementDecisionId)
        assertEquals(20L, result.expectedUpdatedAt)
        assertEquals(21L, result.actualUpdatedAt)
        assertEquals(before, dao.rows)
        assertEquals(DecisionState.ACTIVE.name, dao.get("a")!!.decisionState)
        assertEquals("texto novo", dao.get("b")!!.text)
        assertNull(dao.get("b")!!.supersedesId)
        assertEquals(0, dao.linkCalls)
    }

    @Test
    fun substitutaComMesmoTimestampETextoNovoNaoVincula() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", text = "texto exibido", createdAt = 20L, updatedAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!
        dao.upsert(replacement.copy(text = "texto novo").toEntity())

        val result = service.confirm(prompt, now = 30L)

        assertTrue(result is SupersessionResult.ReplacementStale)
        assertNull(dao.get("b")!!.supersedesId)
        assertEquals(0, dao.linkCalls)
    }

    @Test
    fun substitutaVinculadaAOutraDecisaoConflitaSemEscrita() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", createdAt = 20L, updatedAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!
        dao.upsert(replacement.copy(supersedesId = "c").toEntity())
        val before = dao.rows

        val result = service.confirm(prompt, now = 30L)

        assertTrue(result is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.REPLACEMENT_ALREADY_LINKED,
            (result as SupersessionResult.Conflict).reason,
        )
        assertEquals(before, dao.rows)
        assertEquals(0, dao.linkCalls)
    }

    @Test
    fun refreshAposMudancaDaSubstitutaAtualizaORetrato() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", text = "texto exibido", createdAt = 20L, updatedAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!
        dao.upsert(replacement.copy(text = "texto novo", updatedAt = 21L).toEntity())

        val refreshed = service.refresh(prompt)!!

        assertEquals("texto novo", refreshed.replacement.text)
        assertEquals(21L, refreshed.replacement.updatedAt)

        val result = service.confirm(refreshed, now = 30L)

        assertTrue(result is SupersessionResult.Committed)
        assertEquals("a", dao.get("b")!!.supersedesId)
    }

    @Test
    fun refreshRelêTimestampDaAnterior() = runBlocking {
        val previous = decision("a", updatedAt = 15L)
        val replacement = decision("b", createdAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!

        dao.upsert(previous.copy(updatedAt = 16L).toEntity())
        val refreshed = service.refresh(prompt)

        assertNotNull(refreshed)
        assertEquals("a", refreshed!!.previous.id)
        assertEquals(16L, refreshed.toConfirmation(now = 30L).expectedPreviousUpdatedAt)
    }

    @Test
    fun refreshSemAnteriorAtivaEncerraPergunta() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", createdAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!

        dao.upsert(previous.copy(decisionState = DecisionState.SUPERSEDED).toEntity())

        assertNull(service.refresh(prompt))
    }

    @Test
    fun refreshComRegistroRemovidoEncerraPergunta() = runBlocking {
        val previous = decision("a")
        val replacement = decision("b", createdAt = 20L)
        seed(previous, replacement)
        val prompt = service.findPrompt(replacement)!!

        dao.deleteById("b")

        assertNull(service.refresh(prompt))
    }
}
