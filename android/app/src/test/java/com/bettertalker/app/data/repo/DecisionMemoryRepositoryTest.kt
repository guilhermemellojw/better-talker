package com.bettertalker.app.data.repo

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.ReconcileOperation
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.SectionSnapshot
import com.bettertalker.app.data.db.DecisionDao
import com.bettertalker.app.data.db.DecisionEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDecisionDao : DecisionDao {
    var rows: List<DecisionEntity> = emptyList()
    var upsertCalls = 0
    var upsertAllCalls = 0
    var failOnIds: Set<String> = emptySet()

    override suspend fun forNote(noteId: String): List<DecisionEntity> =
        rows.filter { it.noteId == noteId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun get(id: String): DecisionEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun upsert(record: DecisionEntity) {
        if (record.id in failOnIds) error("falha simulada em ${record.id}")
        upsertCalls++
        rows = rows.filter { it.id != record.id } + record
    }

    override suspend fun upsertAll(records: List<DecisionEntity>) {
        upsertAllCalls++
        records.forEach { upsert(it) }
    }

    override suspend fun insertIfAbsent(record: DecisionEntity): Long {
        if (rows.any { it.id == record.id }) return -1L
        rows = rows + record
        return rows.size.toLong()
    }

    override suspend fun markSupersededIfActive(id: String, updatedAt: Long): Int {
        val existing = rows.firstOrNull { it.id == id } ?: return 0
        if (existing.decisionState != "ACTIVE") return 0
        rows = rows.map {
            if (it.id == id) {
                it.copy(decisionState = "SUPERSEDED", updatedAt = updatedAt)
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

/** Simula rollback restaurando o estado do fake quando o bloco falha. */
private class RollbackTransactionRunner(
    private val dao: FakeDecisionDao,
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

/** 1C.2.1A2 — repositório transacional sobre DAO fake (JVM, sem Android). */
class DecisionMemoryRepositoryTest {

    private lateinit var dao: FakeDecisionDao
    private lateinit var repo: DecisionMemoryRepository

    private fun setUp() {
        dao = FakeDecisionDao()
        repo = DecisionMemoryRepository(dao, RollbackTransactionRunner(dao))
    }

    private fun snap(
        id: String = "s1",
        noteId: String = "n1",
        title: String = "Ponto A",
        order: Int = 1,
        hash: String = "hash-a",
    ) = SectionSnapshot(
        id = id,
        noteId = noteId,
        role = SectionRole.BODY,
        title = title,
        order = order,
        contentHash = hash,
    )

    private fun sectionDecision(
        id: String = "d1",
        noteId: String = "n1",
        sectionId: String = "s1",
        originalSectionId: String = sectionId,
        candidateSectionId: String? = null,
        link: SectionLink = SectionLink.VALID,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        title: String = "Ponto A",
        order: Int = 1,
        hash: String = "hash-a",
        originalTitle: String = title,
        originalOrder: Int = order,
        originalHash: String = hash,
        supersedesId: String? = null,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
    ) = DecisionRecord(
        id = id,
        noteId = noteId,
        scope = DecisionScope.SECTION,
        kind = kind,
        decisionState = state,
        sectionLink = link,
        applicationState = application,
        text = "usar contraste na abertura",
        source = DecisionSource.EXPLICIT_USER,
        sectionId = sectionId,
        originalSectionId = originalSectionId,
        candidateSectionId = candidateSectionId,
        sectionTitleSnapshot = title,
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = order,
        sectionContentHashSnapshot = hash,
        originalSectionTitleSnapshot = originalTitle,
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = originalOrder,
        originalSectionContentHashSnapshot = originalHash,
        supersedesId = supersedesId,
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 11L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) "aplicado-hash" else null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
    )

    private fun noteDecision(
        id: String = "g1",
        noteId: String = "n1",
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
    ) = DecisionRecord(
        id = id,
        noteId = noteId,
        scope = DecisionScope.NOTE,
        kind = kind,
        decisionState = state,
        sectionLink = SectionLink.NOT_APPLICABLE,
        applicationState = application,
        text = "tom pastoral em toda a nota",
        source = DecisionSource.EXPLICIT_USER,
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 11L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) "aplicado-hash" else null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
    )

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        val result = runCatching { block() }
        assertTrue("esperava uma falha", result.isFailure)
        return result.exceptionOrNull()!!
    }

    @Test
    fun savePreservaTodosOsCamposERoundTrip() = runBlocking {
        setUp()
        val remapped = sectionDecision(
            sectionId = "s2",
            originalSectionId = "s1",
            title = "Ponto B",
            order = 2,
            hash = "hash-b",
            originalTitle = "Ponto A",
            originalOrder = 1,
            originalHash = "hash-a",
            application = ApplicationState.STALE,
            updatedAt = 20L,
        )
        repo.save(remapped)
        assertEquals(remapped, dao.rows.single().toDomain())
        assertEquals(remapped, repo.get("d1"))
        assertEquals(listOf(remapped), repo.forNote("n1"))
    }

    @Test
    fun saveIdempotenteNaoRegrava() = runBlocking {
        setUp()
        val d = sectionDecision()
        repo.save(d)
        assertEquals(1, dao.upsertCalls)
        repo.save(d)
        assertEquals(1, dao.upsertCalls)
    }

    @Test
    fun saveRejeitaRegressaoDeUpdatedAt() = runBlocking {
        setUp()
        val d = sectionDecision(updatedAt = 20L)
        repo.save(d)
        val error = failureOf {
            repo.save(d.copy(text = "texto antigo", updatedAt = 15L))
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals(d, dao.rows.single().toDomain())
    }

    @Test
    fun saveRejeitaTrocaDeIdentidadeECreatedAtESnapshotOriginal() = runBlocking {
        setUp()
        val d = sectionDecision()
        repo.save(d)

        assertTrue(failureOf { repo.save(d.copy(noteId = "n2")) } is IllegalArgumentException)
        assertTrue(
            failureOf { repo.save(d.copy(createdAt = 5L)) } is IllegalArgumentException,
        )
        assertTrue(
            failureOf {
                repo.save(d.copy(originalSectionTitleSnapshot = "Outro título"))
            } is IllegalArgumentException,
        )
        assertTrue(
            failureOf {
                repo.save(d.copy(originalSectionContentHashSnapshot = "outro-hash"))
            } is IllegalArgumentException,
        )
        assertEquals(d, dao.rows.single().toDomain())
    }

    @Test
    fun saveAllAtomicoComRollback() = runBlocking {
        setUp()
        val d1 = sectionDecision(id = "d1")
        val d2 = sectionDecision(id = "d2")
        dao.failOnIds = setOf("d2")

        val error = failureOf { repo.saveAll(listOf(d1, d2)) }
        assertTrue(error is IllegalStateException)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun saveAllRejeitaIdRepetido() = runBlocking {
        setUp()
        val d = sectionDecision()
        val error = failureOf {
            repo.saveAll(listOf(d, d.copy(text = "outro texto")))
        }
        assertTrue(error is IllegalArgumentException)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun replaceForNoteSubstituiSomenteANota() = runBlocking {
        setUp()
        val d1 = sectionDecision(id = "d1", noteId = "n1")
        val d2 = sectionDecision(id = "d2", noteId = "n2")
        repo.saveAll(listOf(d1, d2))

        val d3 = sectionDecision(id = "d3", noteId = "n1")
        repo.replaceForNote("n1", listOf(d3))

        assertEquals(listOf(d3), repo.forNote("n1"))
        assertEquals(listOf(d2), repo.forNote("n2"))
    }

    @Test
    fun replaceForNoteAtomicoComRollback() = runBlocking {
        setUp()
        val old = sectionDecision(id = "d1")
        repo.save(old)
        val incoming = sectionDecision(id = "d2")
        dao.failOnIds = setOf("d2")

        val error = failureOf { repo.replaceForNote("n1", listOf(incoming)) }
        assertTrue(error is IllegalStateException)
        assertEquals(listOf(old), repo.forNote("n1"))
    }

    @Test
    fun replaceForNoteRejeitaDecisaoDeOutraNota() = runBlocking {
        setUp()
        val d = sectionDecision(id = "d1", noteId = "n1")
        repo.save(d)
        val error = failureOf {
            repo.replaceForNote("n1", listOf(sectionDecision(id = "d2", noteId = "n2")))
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals(listOf(d), repo.forNote("n1"))
    }

    @Test
    fun replaceForNoteComListaVaziaLimpaSomenteANota() = runBlocking {
        setUp()
        val d1 = sectionDecision(id = "d1", noteId = "n1")
        val d2 = sectionDecision(id = "d2", noteId = "n2")
        repo.saveAll(listOf(d1, d2))

        repo.replaceForNote("n1", emptyList())

        assertTrue(repo.forNote("n1").isEmpty())
        assertEquals(listOf(d2), repo.forNote("n2"))
    }

    @Test
    fun replaceForNoteRejeitaColisaoDeIdComOutraNota() = runBlocking {
        setUp()
        val d1 = sectionDecision(id = "d1", noteId = "n1")
        val d2 = sectionDecision(id = "d2", noteId = "n2")
        repo.saveAll(listOf(d1, d2))
        val incoming = sectionDecision(id = "d3", noteId = "n1")
        val colliding = sectionDecision(id = "d2", noteId = "n1")

        val error = failureOf {
            repo.replaceForNote("n1", listOf(incoming, colliding))
        }

        assertTrue(error is IllegalArgumentException)
        // Nem a nota n1 foi substituída nem a decisão d2 foi transferida.
        assertEquals(listOf(d1), repo.forNote("n1"))
        assertEquals(listOf(d2), repo.forNote("n2"))
    }

    @Test
    fun replaceForNotePermiteReutilizarIdDaMesmaNota() = runBlocking {
        setUp()
        val d1 = sectionDecision(id = "d1", noteId = "n1")
        repo.save(d1)
        val updated = d1.copy(text = "texto atualizado")

        repo.replaceForNote("n1", listOf(updated))

        assertEquals(listOf(updated), repo.forNote("n1"))
    }

    @Test
    fun reconcileSemSnapshotsOrfanaSomenteANotaAfetada() = runBlocking {
        setUp()
        val d1 = sectionDecision(id = "d1", noteId = "n1")
        val d2 = sectionDecision(id = "d2", noteId = "n2")
        repo.saveAll(listOf(d1, d2))

        val report = repo.reconcile(
            noteId = "n1",
            oldSections = emptyList(),
            newSections = emptyList(),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        )

        val result = report.decisions.single()
        assertEquals(SectionLink.ORPHANED, result.sectionLink)
        assertEquals("s1", result.sectionId)
        assertEquals(20L, result.updatedAt)
        assertEquals(result, repo.get("d1"))
        assertEquals(d2, repo.forNote("n2").single())
    }

    @Test
    fun reconcileReimportReplaceComOldNaoVazioENewVazioOrfana() = runBlocking {
        setUp()
        val d = sectionDecision(application = ApplicationState.APPLIED)
        repo.save(d)

        val report = repo.reconcile(
            noteId = "n1",
            oldSections = listOf(snap()),
            newSections = emptyList(),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        )

        val stored = repo.get("d1")!!
        assertEquals(SectionLink.ORPHANED, stored.sectionLink)
        assertNull(stored.candidateSectionId)
        assertEquals(ApplicationState.APPLIED, stored.applicationState)
        assertEquals(20L, stored.updatedAt)
        assertEquals(stored, report.decisions.single())
    }

    @Test
    fun removeSeguidoDeUndoReassociaSecao() = runBlocking {
        setUp()
        repo.save(sectionDecision())

        repo.reconcile(
            noteId = "n1",
            oldSections = listOf(snap()),
            newSections = emptyList(),
            operation = ReconcileOperation.REMOVE,
            now = 20L,
        )
        assertEquals(SectionLink.ORPHANED, repo.get("d1")!!.sectionLink)

        repo.reconcile(
            noteId = "n1",
            oldSections = emptyList(),
            newSections = listOf(snap()),
            operation = ReconcileOperation.UNDO_REMOVE,
            now = 30L,
        )
        val restored = repo.get("d1")!!
        assertEquals(SectionLink.VALID, restored.sectionLink)
        assertEquals("s1", restored.sectionId)
        assertEquals(30L, restored.updatedAt)
    }

    @Test
    fun reconcileSemMudancaNaoGravaNemAtualizaTimestamp() = runBlocking {
        setUp()
        val d = sectionDecision(updatedAt = 10L)
        repo.save(d)
        val upsertsBefore = dao.upsertAllCalls

        val report = repo.reconcile(
            noteId = "n1",
            oldSections = listOf(snap()),
            newSections = listOf(snap()),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )

        assertEquals(d, report.decisions.single())
        assertEquals(10L, dao.rows.single().updatedAt)
        assertEquals(upsertsBefore, dao.upsertAllCalls)
    }

    @Test
    fun reconcilePersisteMudancaEPreservaSnapshotOriginal() = runBlocking {
        setUp()
        val d = sectionDecision()
        repo.save(d)

        val report = repo.reconcile(
            noteId = "n1",
            oldSections = listOf(snap(title = "Ponto A", order = 1, hash = "hash-a")),
            newSections = listOf(snap(title = "Ponto B", order = 2, hash = "hash-b")),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )

        val stored = dao.rows.single().toDomain()
        assertEquals("Ponto B", stored.sectionTitleSnapshot)
        assertEquals(2, stored.sectionOrderSnapshot)
        assertEquals("hash-b", stored.sectionContentHashSnapshot)
        assertEquals("Ponto A", stored.originalSectionTitleSnapshot)
        assertEquals(1, stored.originalSectionOrderSnapshot)
        assertEquals("hash-a", stored.originalSectionContentHashSnapshot)
        assertEquals(20L, stored.updatedAt)
        assertEquals(stored, report.decisions.single())
    }

    @Test
    fun reconcileOrfaaRepetidaNaoAtualizaTimestamp() = runBlocking {
        setUp()
        val orphan = sectionDecision(link = SectionLink.ORPHANED, updatedAt = 10L)
        repo.save(orphan)

        val report = repo.reconcile(
            noteId = "n1",
            oldSections = listOf(snap()),
            newSections = emptyList(),
            operation = ReconcileOperation.REMOVE,
            now = 20L,
        )

        assertEquals(orphan, report.decisions.single())
        assertEquals(10L, dao.rows.single().updatedAt)
    }

    @Test
    fun reconcileCarregaSomenteADecisaoDaNota() = runBlocking {
        setUp()
        val d1 = sectionDecision(id = "d1", noteId = "n1")
        val d2 = sectionDecision(id = "d2", noteId = "n2")
        repo.saveAll(listOf(d1, d2))

        repo.reconcile(
            noteId = "n1",
            oldSections = listOf(snap(noteId = "n1")),
            newSections = emptyList(),
            operation = ReconcileOperation.REMOVE,
            now = 20L,
        )

        assertEquals(listOf(d2), repo.forNote("n2"))
        assertEquals(d2, dao.rows.first { it.id == "d2" }.toDomain())
    }

    @Test
    fun reconcileRejeitaSecaoDeOutraNota() = runBlocking {
        setUp()
        val error = failureOf {
            repo.reconcile(
                noteId = "n1",
                oldSections = listOf(snap(noteId = "n2")),
                newSections = emptyList(),
                operation = ReconcileOperation.REMOVE,
                now = 20L,
            )
        }
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun confirmRemapPersisteCandidatoSugeridoEPreservaOriginal() = runBlocking {
        setUp()
        val pending = sectionDecision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
            application = ApplicationState.APPLIED,
        )
        repo.save(pending)

        val result = repo.confirmRemap(
            decisionId = "d1",
            target = snap(id = "s2", title = "Ponto B", order = 2, hash = "hash-b"),
            now = 20L,
        )!!

        val stored = dao.rows.single().toDomain()
        assertEquals("s2", stored.sectionId)
        assertEquals("s1", stored.originalSectionId)
        assertEquals(SectionLink.VALID, stored.sectionLink)
        assertNull(stored.candidateSectionId)
        assertEquals("Ponto B", stored.sectionTitleSnapshot)
        assertEquals("Ponto A", stored.originalSectionTitleSnapshot)
        assertEquals(ApplicationState.STALE, stored.applicationState)
        assertEquals(result, stored)
    }

    @Test
    fun confirmRemapRejeitaCandidatoDiferenteSemGravar() = runBlocking {
        setUp()
        val pending = sectionDecision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )
        repo.save(pending)

        val error = failureOf {
            repo.confirmRemap(
                decisionId = "d1",
                target = snap(id = "s3", title = "Ponto C", order = 3),
                now = 20L,
            )
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals(pending, dao.rows.single().toDomain())
    }

    @Test
    fun confirmManualRemapEhIdempotente() = runBlocking {
        setUp()
        val pending = sectionDecision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )
        repo.save(pending)
        val target = snap(id = "s3", title = "Ponto C", order = 3)

        val first = repo.confirmManualRemap("d1", target, now = 20L)!!
        val upsertsAfterFirst = dao.upsertCalls
        val second = repo.confirmManualRemap("d1", target, now = 30L)!!

        assertEquals(first, second)
        assertEquals(upsertsAfterFirst, dao.upsertCalls)
        assertEquals(20L, second.updatedAt)
        assertEquals("s1", second.originalSectionId)
    }

    @Test
    fun supersedePersisteParEPreservaHistorico() = runBlocking {
        setUp()
        val previous = sectionDecision(id = "d1")
        repo.save(previous)
        val replacement = sectionDecision(
            id = "d2",
            supersedesId = "d1",
            updatedAt = 10L,
        )

        val pair = repo.supersede("d1", replacement, now = 20L)!!

        assertEquals(DecisionState.SUPERSEDED, pair.previous.decisionState)
        assertEquals(DecisionState.ACTIVE, pair.replacement.decisionState)
        assertEquals(2, dao.rows.size)
        assertEquals(
            DecisionState.SUPERSEDED,
            dao.rows.first { it.id == "d1" }.toDomain().decisionState,
        )
        assertEquals(20L, dao.rows.first { it.id == "d1" }.updatedAt)
        assertEquals("d1", dao.rows.first { it.id == "d2" }.supersedesId)
    }

    @Test
    fun supersedeAtomicoComRollback() = runBlocking {
        setUp()
        val previous = sectionDecision(id = "d1")
        repo.save(previous)
        val replacement = sectionDecision(id = "d2", supersedesId = "d1")
        dao.failOnIds = setOf("d2")

        val error = failureOf { repo.supersede("d1", replacement, now = 20L) }
        assertTrue(error is IllegalStateException)
        assertEquals(previous, dao.rows.single().toDomain())
        assertNull(dao.rows.firstOrNull { it.id == "d2" })
    }

    @Test
    fun remocaoReversivelPreservaAplicacaoHistorica() = runBlocking {
        setUp()
        val applied = sectionDecision(application = ApplicationState.APPLIED)
        repo.save(applied)

        val orphan = repo.orphanForReversibleRemoval("d1", now = 20L)!!

        assertEquals(SectionLink.ORPHANED, orphan.sectionLink)
        assertEquals(ApplicationState.APPLIED, orphan.applicationState)
        assertEquals(11L, orphan.appliedAt)
        assertEquals("aplicado-hash", orphan.appliedHash)
        assertEquals(orphan, dao.rows.single().toDomain())
    }

    @Test
    fun remocaoPermanenteEhEstadoLogicoPersistido() = runBlocking {
        setUp()
        repo.save(noteDecision())

        val removed = repo.removePermanently("g1", now = 20L)!!

        assertEquals(DecisionState.REMOVED, removed.decisionState)
        assertEquals(20L, removed.removedAt)
        assertEquals(removed, dao.rows.single().toDomain())
    }
}
