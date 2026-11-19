package com.bettertalker.app.data.repo

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.DecisionSubject
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.SupersessionAmbiguityReason
import com.bettertalker.app.data.copilot.SupersessionConflictReason
import com.bettertalker.app.data.copilot.SupersessionConfirmation
import com.bettertalker.app.data.copilot.SupersessionIneligibleReason
import com.bettertalker.app.data.copilot.SupersessionResult
import com.bettertalker.app.data.copilot.hasCurrentApplication
import com.bettertalker.app.data.copilot.isEligibleForPromptMemory
import com.bettertalker.app.data.db.DecisionDao
import com.bettertalker.app.data.db.DecisionEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class SupersessionFakeDao : DecisionDao {
    var rows: List<DecisionEntity> = emptyList()
    var insertCalls = 0
    var markCalls = 0
    var failOnMark = false
    var forceMarkZero = false

    override suspend fun forNote(noteId: String): List<DecisionEntity> =
        rows.filter { it.noteId == noteId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun get(id: String): DecisionEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun upsert(record: DecisionEntity) {
        rows = rows.filter { it.id != record.id } + record
    }

    override suspend fun upsertAll(records: List<DecisionEntity>) {
        records.forEach { upsert(it) }
    }

    override suspend fun insertIfAbsent(record: DecisionEntity): Long {
        if (rows.any { it.id == record.id }) return -1L
        insertCalls++
        rows = rows + record
        return rows.size.toLong()
    }

    override suspend fun markSupersededIfActive(id: String, updatedAt: Long): Int {
        if (failOnMark) error("falha simulada no compare-and-set")
        markCalls++
        if (forceMarkZero) return 0
        val existing = rows.firstOrNull { it.id == id } ?: return 0
        if (existing.decisionState != DecisionState.ACTIVE.name) return 0
        rows = rows.map {
            if (it.id == id) {
                it.copy(
                    decisionState = DecisionState.SUPERSEDED.name,
                    updatedAt = updatedAt,
                )
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun deleteById(id: String) {
        rows = rows.filter { it.id != id }
    }

    override suspend fun deleteForNote(noteId: String) {
        rows = rows.filter { it.noteId != noteId }
    }

    override suspend fun countForNote(noteId: String): Int =
        rows.count { it.noteId == noteId }
}

private class SupersessionRollbackRunner(
    private val dao: SupersessionFakeDao,
) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R {
        val before = dao.rows
        return try {
            block()
        } catch (failure: Throwable) {
            dao.rows = before
            throw failure
        }
    }
}

/** 1C.2.1B2B — substituição confirmada, CAS e idempotência. */
class DecisionSupersessionTest {

    private lateinit var dao: SupersessionFakeDao
    private lateinit var repo: DecisionMemoryRepository

    @Before
    fun setUp() {
        dao = SupersessionFakeDao()
        repo = DecisionMemoryRepository(dao, SupersessionRollbackRunner(dao))
    }

    private fun decision(
        id: String,
        noteId: String = "n1",
        scope: DecisionScope = DecisionScope.SECTION,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        link: SectionLink =
            if (scope == DecisionScope.SECTION) SectionLink.VALID else SectionLink.NOT_APPLICABLE,
        sectionId: String? = if (scope == DecisionScope.SECTION) "s1" else null,
        originalSectionId: String? = sectionId,
        candidateSectionId: String? = null,
        subjectKey: String? = if (scope == DecisionScope.SECTION) {
            DecisionSubject.SECTION_APPROACH.key
        } else {
            DecisionSubject.NOTE_TONE.key
        },
        source: DecisionSource = DecisionSource.STRUCTURE_ACCEPT,
        sourceProposalId: String? = "prop-$id",
        sourceMessageId: String? = null,
        text: String = "texto $id",
        supersedesId: String? = null,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
    ): DecisionRecord {
        val section = scope == DecisionScope.SECTION
        return DecisionRecord(
            id = id,
            noteId = noteId,
            scope = scope,
            kind = kind,
            decisionState = state,
            sectionLink = link,
            applicationState = application,
            text = text,
            source = source,
            sectionId = sectionId,
            originalSectionId = originalSectionId,
            candidateSectionId = candidateSectionId,
            sectionTitleSnapshot = if (section) "Ponto 1" else null,
            sectionRoleSnapshot = if (section) SectionRole.BODY else null,
            sectionOrderSnapshot = if (section) 1 else null,
            sectionContentHashSnapshot = if (section) "hash-s1" else null,
            originalSectionTitleSnapshot = if (section) "Ponto 1" else null,
            originalSectionRoleSnapshot = if (section) SectionRole.BODY else null,
            originalSectionOrderSnapshot = if (section) 1 else null,
            originalSectionContentHashSnapshot = if (section) "hash-s1" else null,
            subjectKey = subjectKey,
            sourceMessageId = sourceMessageId,
            sourceProposalId = sourceProposalId,
            supersedesId = supersedesId,
            appliedAt = if (application != ApplicationState.NOT_APPLIED) 11L else null,
            appliedHash = if (application != ApplicationState.NOT_APPLIED) "hash-aplicado" else null,
            createdAt = createdAt,
            updatedAt = updatedAt,
            removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
        )
    }

    private fun confirmation(
        previous: DecisionRecord,
        replacement: DecisionRecord,
        noteId: String = previous.noteId,
        expectedUpdatedAt: Long = previous.updatedAt,
        expectedState: DecisionState = DecisionState.ACTIVE,
        id: String = "conf-${previous.id}-${replacement.id}",
    ) = SupersessionConfirmation(
        confirmationId = id,
        noteId = noteId,
        previousDecisionId = previous.id,
        replacementDecisionId = replacement.id,
        expectedPreviousUpdatedAt = expectedUpdatedAt,
        expectedPreviousState = expectedState,
        confirmedAt = 20L,
    )

    private fun seed(vararg decisions: DecisionRecord) {
        dao.rows = decisions.map { it.toEntity() }
    }

    @Test
    fun substituicaoValidaCommitaParEPreservaHistorico() = runBlocking {
        val previous = decision(id = "a", createdAt = 10L)
        val replacement = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Committed)
        val storedPrevious = repo.get("a")!!
        val storedReplacement = repo.get("b")!!
        assertEquals(DecisionState.SUPERSEDED, storedPrevious.decisionState)
        assertEquals(DecisionState.ACTIVE, storedReplacement.decisionState)
        assertEquals(30L, storedPrevious.updatedAt)
        assertEquals("a", storedReplacement.supersedesId)
        assertEquals(20L, storedReplacement.createdAt)
        assertEquals(30L, storedReplacement.updatedAt)
        assertEquals(2, dao.rows.size)
        assertEquals(1, dao.insertCalls)
        assertEquals(1, dao.markCalls)
    }

    @Test
    fun repeticaoIdenticaEhAlreadyAppliedSemAlterarNada() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        seed(previous)
        val confirmation = confirmation(previous, replacement)
        repo.supersedeConfirmed(confirmation, replacement, now = 30L)
        val snapshot = dao.rows

        val repeated = repo.supersedeConfirmed(confirmation, replacement, now = 40L)

        assertTrue(repeated is SupersessionResult.AlreadyApplied)
        assertEquals(snapshot, dao.rows)
        assertEquals(30L, repo.get("a")!!.updatedAt)
        assertEquals(1, dao.insertCalls)
        assertEquals(1, dao.markCalls)
    }

    @Test
    fun aParaBDepoisAParaCConflitaSemCriarC() = runBlocking {
        val previous = decision(id = "a")
        val b = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        val c = decision(id = "c", createdAt = 20L, updatedAt = 20L)
        seed(previous)
        repo.supersedeConfirmed(confirmation(previous, b), b, now = 30L)

        val result = repo.supersedeConfirmed(
            confirmation(previous, c),
            c,
            now = 40L,
        )

        assertTrue(result is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.PREVIOUS_SUPERSEDED_BY_OTHER,
            (result as SupersessionResult.Conflict).reason,
        )
        assertNull(repo.get("c"))
        assertEquals(DecisionState.SUPERSEDED, repo.get("a")!!.decisionState)
        assertEquals("a", repo.get("b")!!.supersedesId)
    }

    @Test
    fun colisaoDeIdComDecisaoExistenteConflita() = runBlocking {
        val previous = decision(id = "a")
        val existingB = decision(id = "b", text = "outro conteúdo")
        val incomingB = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        seed(previous, existingB)

        val result = repo.supersedeConfirmed(
            confirmation(previous, incomingB),
            incomingB,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.REPLACEMENT_ID_COLLISION,
            (result as SupersessionResult.Conflict).reason,
        )
        assertEquals(DecisionState.ACTIVE, repo.get("a")!!.decisionState)
        assertEquals("outro conteúdo", repo.get("b")!!.text)
    }

    @Test
    fun decisaoDeOutraNotaEhIneligible() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", noteId = "n2", createdAt = 20L)
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.DIFFERENT_NOTE,
            (result as SupersessionResult.Ineligible).reason,
        )
        assertEquals(DecisionState.ACTIVE, repo.get("a")!!.decisionState)
        assertNull(repo.get("b"))
    }

    @Test
    fun escoposDiferentesEhIneligible() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(
            id = "b",
            scope = DecisionScope.NOTE,
            source = DecisionSource.EXPLICIT_USER,
            createdAt = 20L,
        )
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.DIFFERENT_SCOPE,
            (result as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun assuntosDiferentesEhIneligible() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(
            id = "b",
            subjectKey = DecisionSubject.SECTION_OPENING.key,
            createdAt = 20L,
        )
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.DIFFERENT_SUBJECT,
            (result as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun anteriorRemovidaEhIneligible() = runBlocking {
        val previous = decision(id = "a", state = DecisionState.REMOVED)
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.PREVIOUS_NOT_ACTIVE,
            (result as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun substitutaRemovidaOuNaoConfirmadaEhIneligible() = runBlocking {
        val previous = decision(id = "a")
        val removed = decision(id = "b", state = DecisionState.REMOVED, createdAt = 20L)
        seed(previous)

        val removedResult = repo.supersedeConfirmed(
            confirmation(previous, removed),
            removed,
            now = 30L,
        )
        assertTrue(removedResult is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.REPLACEMENT_NOT_ACTIVE,
            (removedResult as SupersessionResult.Ineligible).reason,
        )

        val preference = decision(
            id = "c",
            kind = DecisionKind.PREFERENCE,
            source = DecisionSource.EXPLICIT_USER,
            createdAt = 20L,
        )
        val preferenceResult = repo.supersedeConfirmed(
            confirmation(previous, preference),
            preference,
            now = 30L,
        )
        assertTrue(preferenceResult is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.NOT_CONFIRMED,
            (preferenceResult as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun decisoesOpenNaoParticipam() = runBlocking {
        val previousOpen = decision(
            id = "a",
            kind = DecisionKind.OPEN,
            source = DecisionSource.EXPLICIT_USER,
        )
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previousOpen)

        val result = repo.supersedeConfirmed(
            confirmation(previousOpen, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.NOT_CONFIRMED,
            (result as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun orfaEPendenteNaoParticipam() = runBlocking {
        val replacement = decision(id = "b", createdAt = 20L)
        val orphan = decision(id = "a", link = SectionLink.ORPHANED)
        seed(orphan)

        val orphanResult = repo.supersedeConfirmed(
            confirmation(orphan, replacement),
            replacement,
            now = 30L,
        )
        assertTrue(orphanResult is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.INVALID_SECTION_LINK,
            (orphanResult as SupersessionResult.Ineligible).reason,
        )

        val pending = decision(
            id = "p",
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
            createdAt = 20L,
        )
        seed(pending)
        val pendingResult = repo.supersedeConfirmed(
            confirmation(pending, replacement),
            replacement,
            now = 30L,
        )
        assertTrue(pendingResult is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.INVALID_SECTION_LINK,
            (pendingResult as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun identidadeSemProvenienciaSuficienteEhAmbiguous() = runBlocking {
        val previous = decision(
            id = "a",
            source = DecisionSource.EXPLICIT_USER,
            sourceProposalId = null,
            sourceMessageId = null,
        )
        val replacement = decision(
            id = "b",
            source = DecisionSource.EXPLICIT_USER,
            sourceProposalId = null,
            sourceMessageId = null,
            createdAt = 20L,
        )
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Ambiguous)
        assertEquals(
            SupersessionAmbiguityReason.MISSING_STABLE_ORIGIN,
            (result as SupersessionResult.Ambiguous).reason,
        )
        assertEquals(DecisionState.ACTIVE, repo.get("a")!!.decisionState)
    }

    @Test
    fun mesmaPropostaOuMensagemEhConflitoDeOrigem() = runBlocking {
        val previous = decision(id = "a", sourceProposalId = "prop-1")
        val sameProposal = decision(
            id = "b",
            sourceProposalId = "prop-1",
            createdAt = 20L,
        )
        seed(previous)
        val proposalResult = repo.supersedeConfirmed(
            confirmation(previous, sameProposal),
            sameProposal,
            now = 30L,
        )
        assertTrue(proposalResult is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.ORIGIN_COLLISION,
            (proposalResult as SupersessionResult.Conflict).reason,
        )

        val previousMessage = decision(
            id = "m1",
            source = DecisionSource.EXPLICIT_USER,
            sourceProposalId = null,
            sourceMessageId = "msg-1",
        )
        val sameMessage = decision(
            id = "m2",
            source = DecisionSource.EXPLICIT_USER,
            sourceProposalId = null,
            sourceMessageId = "msg-1",
            createdAt = 20L,
        )
        seed(previousMessage)
        val messageResult = repo.supersedeConfirmed(
            confirmation(previousMessage, sameMessage),
            sameMessage,
            now = 30L,
        )
        assertTrue(messageResult is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.ORIGIN_COLLISION,
            (messageResult as SupersessionResult.Conflict).reason,
        )
    }

    @Test
    fun confirmacaoDivergenteEhConflitoOuIneligible() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)

        val wrongReplacementNote = repo.supersedeConfirmed(
            confirmation(previous, replacement, noteId = "n2"),
            replacement,
            now = 30L,
        )
        assertTrue(wrongReplacementNote is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.DIFFERENT_NOTE,
            (wrongReplacementNote as SupersessionResult.Ineligible).reason,
        )

        val replacementN2 = replacement.copy(noteId = "n2")
        val wrongPreviousNote = repo.supersedeConfirmed(
            confirmation(previous, replacementN2, noteId = "n2"),
            replacementN2,
            now = 30L,
        )
        assertTrue(wrongPreviousNote is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.CONFIRMATION_MISMATCH,
            (wrongPreviousNote as SupersessionResult.Conflict).reason,
        )

        val wrongState = repo.supersedeConfirmed(
            confirmation(previous, replacement, expectedState = DecisionState.SUPERSEDED),
            replacement,
            now = 30L,
        )
        assertTrue(wrongState is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.INVALID_CONFIRMATION,
            (wrongState as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun estadoStaleNaoAlteraNada() = runBlocking {
        val previous = decision(id = "a", updatedAt = 10L)
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement, expectedUpdatedAt = 11L),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Stale)
        assertEquals(10L, (result as SupersessionResult.Stale).actualUpdatedAt)
        assertEquals(DecisionState.ACTIVE, repo.get("a")!!.decisionState)
        assertNull(repo.get("b"))
    }

    @Test
    fun falhaEntreInsertEUpdateFazRollback() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)
        dao.failOnMark = true

        val failure = runCatching {
            repo.supersedeConfirmed(
                confirmation(previous, replacement),
                replacement,
                now = 30L,
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(DecisionState.ACTIVE, repo.get("a")!!.decisionState)
        assertNull(repo.get("b"))
    }

    @Test
    fun compareAndSetPerdidoFazRollbackESemSucesso() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)
        dao.forceMarkZero = true

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.CONCURRENT_SUPERSESSION,
            (result as SupersessionResult.Conflict).reason,
        )
        assertEquals(DecisionState.ACTIVE, repo.get("a")!!.decisionState)
        assertNull(repo.get("b"))
    }

    @Test
    fun preservaAplicacaoESnapshotsDaAnterior() = runBlocking {
        val previous = decision(
            id = "a",
            application = ApplicationState.APPLIED,
        )
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Committed)
        val stored = repo.get("a")!!
        assertEquals(DecisionState.SUPERSEDED, stored.decisionState)
        assertEquals(ApplicationState.APPLIED, stored.applicationState)
        assertEquals(11L, stored.appliedAt)
        assertEquals("hash-aplicado", stored.appliedHash)
        assertEquals(previous.sectionTitleSnapshot, stored.sectionTitleSnapshot)
        assertEquals(
            previous.originalSectionContentHashSnapshot,
            stored.originalSectionContentHashSnapshot,
        )
        assertFalse(stored.hasCurrentApplication())
        assertFalse(stored.isEligibleForPromptMemory())
    }

    @Test
    fun remapeamentoConfirmadoEhAceitoEPreservaOriginais() = runBlocking {
        val previous = decision(
            id = "a",
            sectionId = "s2",
            originalSectionId = "s1",
        )
        val replacement = decision(
            id = "b",
            sectionId = "s2",
            originalSectionId = "s2",
            createdAt = 20L,
        )
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Committed)
        assertEquals("s1", repo.get("a")!!.originalSectionId)
        assertEquals("s2", repo.get("b")!!.originalSectionId)
        assertEquals("s2", repo.get("b")!!.sectionId)
    }

    @Test
    fun secoesOriginaisDivergentesSemRemapSaoIneligible() = runBlocking {
        val previous = decision(
            id = "a",
            sectionId = "s1",
            originalSectionId = "s1",
        )
        val replacement = decision(
            id = "b",
            sectionId = "s1",
            originalSectionId = "s9",
            createdAt = 20L,
        )
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Ineligible)
        assertEquals(
            SupersessionIneligibleReason.DIFFERENT_ORIGINAL_SECTION,
            (result as SupersessionResult.Ineligible).reason,
        )
    }

    @Test
    fun cadeiaAParaBParaCPreservaHistorico() = runBlocking {
        val a = decision(id = "a")
        val b = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        val c = decision(id = "c", createdAt = 30L, updatedAt = 30L)
        seed(a)
        repo.supersedeConfirmed(confirmation(a, b), b, now = 30L)
        val bAfter = repo.get("b")!!

        val result = repo.supersedeConfirmed(
            confirmation(bAfter, c),
            c,
            now = 40L,
        )

        assertTrue(result is SupersessionResult.Committed)
        assertEquals(DecisionState.SUPERSEDED, repo.get("a")!!.decisionState)
        assertEquals(DecisionState.SUPERSEDED, repo.get("b")!!.decisionState)
        assertEquals(DecisionState.ACTIVE, repo.get("c")!!.decisionState)
        assertEquals("a", repo.get("b")!!.supersedesId)
        assertEquals("b", repo.get("c")!!.supersedesId)
        assertEquals(40L, repo.get("b")!!.updatedAt)
        assertEquals(40L, repo.get("c")!!.updatedAt)
    }

    @Test
    fun repeticaoComPayloadDivergenteConflita() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)
        repo.supersedeConfirmed(confirmation(previous, replacement), replacement, now = 30L)
        val before = dao.rows

        val diverged = replacement.copy(text = "conteúdo divergente")
        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            diverged,
            now = 40L,
        )

        assertTrue(result is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.REPLACEMENT_PAYLOAD_DIVERGED,
            (result as SupersessionResult.Conflict).reason,
        )
        assertEquals(before, dao.rows)
    }

    @Test
    fun aplicacaoPosteriorNaoQuebraIdempotencia() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)
        repo.supersedeConfirmed(confirmation(previous, replacement), replacement, now = 30L)
        // A substituta foi aplicada ao texto depois da substituição.
        dao.rows = dao.rows.map {
            if (it.id == "b") {
                it.copy(
                    applicationState = ApplicationState.APPLIED.name,
                    appliedAt = 35L,
                    appliedHash = "hash-b-aplicado",
                )
            } else {
                it
            }
        }
        val afterApplication = dao.rows

        val repeated = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 40L,
        )

        assertTrue(repeated is SupersessionResult.AlreadyApplied)
        assertEquals(afterApplication, dao.rows)
    }

    @Test
    fun anteriorInexistenteEhNotFound() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.NotFound)
        assertEquals("a", (result as SupersessionResult.NotFound).decisionId)
    }
}
