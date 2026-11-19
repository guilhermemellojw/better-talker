package com.bettertalker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.DecisionSubject
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.SupersessionConflictReason
import com.bettertalker.app.data.copilot.SupersessionConfirmation
import com.bettertalker.app.data.copilot.SupersessionResult
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.DecisionDao
import com.bettertalker.app.data.db.DecisionEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1C.2.1B2B — transações Room reais da substituição confirmada.
 *
 * Cobre concorrência A→B/A→C, idempotência, rollback real, colisão de PK,
 * integridade de supersedesId e reabertura do banco.
 */
@RunWith(AndroidJUnit4::class)
class DecisionSupersessionRoomTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: DecisionMemoryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DecisionMemoryRepository(db.decisionDao(), RoomTransactionRunner(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(id: String = "n1") = NoteEntity(
        id = id,
        title = "Nota",
        mdText = "",
        plainText = "",
        folderId = null,
        colorArgb = 0L,
        pinned = false,
        trashed = false,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun decision(
        id: String,
        noteId: String = "n1",
        sectionId: String = "s1",
        originalSectionId: String = sectionId,
        subjectKey: String = DecisionSubject.SECTION_APPROACH.key,
        sourceProposalId: String? = "prop-$id",
        text: String = "texto $id",
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
    ): DecisionRecord = DecisionRecord(
        id = id,
        noteId = noteId,
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = DecisionState.ACTIVE,
        sectionLink = SectionLink.VALID,
        applicationState = application,
        text = text,
        source = DecisionSource.STRUCTURE_ACCEPT,
        sectionId = sectionId,
        originalSectionId = originalSectionId,
        sectionTitleSnapshot = "Ponto 1",
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = 1,
        sectionContentHashSnapshot = "hash-s1",
        originalSectionTitleSnapshot = "Ponto 1",
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = 1,
        originalSectionContentHashSnapshot = "hash-s1",
        subjectKey = subjectKey,
        sourceProposalId = sourceProposalId,
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 11L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) "hash-aplicado" else null,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun confirmation(
        previous: DecisionRecord,
        replacement: DecisionRecord,
        expectedUpdatedAt: Long = previous.updatedAt,
    ) = SupersessionConfirmation(
        confirmationId = "conf-${previous.id}-${replacement.id}",
        noteId = previous.noteId,
        previousDecisionId = previous.id,
        replacementDecisionId = replacement.id,
        expectedPreviousUpdatedAt = expectedUpdatedAt,
        expectedPreviousState = DecisionState.ACTIVE,
        confirmedAt = 20L,
    )

    private suspend fun seed(previous: DecisionRecord) {
        db.noteDao().upsert(note(previous.noteId))
        db.decisionDao().upsert(previous.toEntity())
    }

    @Test
    fun substitutaPersistidaB1EhVinculadaNoRoom() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(
            id = "b",
            createdAt = 20L,
            updatedAt = 20L,
            application = ApplicationState.APPLIED,
        )
        seed(previous)
        db.decisionDao().upsert(replacement.toEntity())
        val before = db.decisionDao().get("b")!!
        val countBefore = db.decisionDao().countForNote("n1")

        val result = repo.supersedePersistedReplacementConfirmed(
            confirmation(previous, replacement),
            expectedReplacement = replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Committed)
        assertEquals(DecisionState.SUPERSEDED, db.decisionDao().get("a")!!.toDomain().decisionState)
        assertEquals("a", db.decisionDao().get("b")!!.supersedesId)
        assertEquals(30L, db.decisionDao().get("b")!!.updatedAt)
        assertEquals(30L, db.decisionDao().get("a")!!.updatedAt)
        // Só o vínculo/timestamp mudam: aplicação, hashes e snapshots intactos.
        assertEquals(
            before.copy(supersedesId = "a", updatedAt = 30L),
            db.decisionDao().get("b"),
        )
        assertEquals(countBefore, db.decisionDao().countForNote("n1"))
    }

    @Test
    fun substitutaAlteradaNoRoomAntesDaConfirmacaoNaoVincula() = runBlocking {
        val previous = decision(id = "a", application = ApplicationState.APPLIED)
        val displayed = decision(
            id = "b",
            text = "texto exibido",
            createdAt = 20L,
            updatedAt = 20L,
            application = ApplicationState.APPLIED,
        )
        seed(previous)
        db.decisionDao().upsert(displayed.toEntity())
        // Outro fluxo altera B depois que o diálogo já tinha o retrato antigo,
        // mantendo o mesmo updatedAt para provar que a versão não é só timestamp.
        db.decisionDao().upsert(
            displayed.copy(text = "texto atualizado no mesmo milissegundo").toEntity(),
        )
        val previousBefore = db.decisionDao().get("a")!!
        val replacementBefore = db.decisionDao().get("b")!!
        val countBefore = db.decisionDao().countForNote("n1")

        val result = repo.supersedePersistedReplacementConfirmed(
            confirmation(previous, displayed),
            expectedReplacement = displayed,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.ReplacementStale)
        result as SupersessionResult.ReplacementStale
        assertEquals("b", result.replacementDecisionId)
        assertEquals(20L, result.expectedUpdatedAt)
        assertEquals(20L, result.actualUpdatedAt)
        assertEquals(previousBefore, db.decisionDao().get("a"))
        assertEquals(replacementBefore, db.decisionDao().get("b"))
        assertEquals(countBefore, db.decisionDao().countForNote("n1"))
        assertEquals(DecisionState.ACTIVE, db.decisionDao().get("a")!!.toDomain().decisionState)
        assertEquals(
            "texto atualizado no mesmo milissegundo",
            db.decisionDao().get("b")!!.toDomain().text,
        )
        assertNull(db.decisionDao().get("b")!!.supersedesId)
    }

    @Test
    fun concorrenciaComSubstitutasPersistidasAceitaSomenteUma() = runBlocking {
        val previous = decision(id = "a")
        val b = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        val c = decision(id = "c", createdAt = 20L, updatedAt = 20L)
        seed(previous)
        db.decisionDao().upsert(b.toEntity())
        db.decisionDao().upsert(c.toEntity())

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    repo.supersedePersistedReplacementConfirmed(
                        confirmation(previous, b),
                        expectedReplacement = b,
                        now = 30L,
                    )
                },
                async(Dispatchers.IO) {
                    repo.supersedePersistedReplacementConfirmed(
                        confirmation(previous, c),
                        expectedReplacement = c,
                        now = 30L,
                    )
                },
            ).awaitAll()
        }

        assertEquals(1, results.count { it is SupersessionResult.Committed })
        assertEquals(1, results.count { it is SupersessionResult.Conflict })
        assertEquals(DecisionState.SUPERSEDED, db.decisionDao().get("a")!!.toDomain().decisionState)
        val bStored = db.decisionDao().get("b")!!.toDomain()
        val cStored = db.decisionDao().get("c")!!.toDomain()
        assertEquals(1, listOf(bStored, cStored).count { it.supersedesId == "a" })
        assertEquals(3, db.decisionDao().countForNote("n1"))
    }

    @Test
    fun substituicaoValidaEIdempotenteNoRoomReal() = runBlocking {
        val previous = decision(id = "a", createdAt = 10L)
        val replacement = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )
        assertTrue(result is SupersessionResult.Committed)
        assertEquals(DecisionState.SUPERSEDED, db.decisionDao().get("a")!!.toDomain().decisionState)
        assertEquals("a", db.decisionDao().get("b")!!.supersedesId)
        assertEquals(30L, db.decisionDao().get("b")!!.updatedAt)

        val repeated = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 40L,
        )
        assertTrue(repeated is SupersessionResult.AlreadyApplied)
        assertEquals(30L, db.decisionDao().get("a")!!.updatedAt)
        assertEquals(30L, db.decisionDao().get("b")!!.updatedAt)
    }

    @Test
    fun repeticaoNoRoomRespeitaVinculoDivergente() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        seed(previous)
        val confirmation = confirmation(previous, replacement)
        assertTrue(
            repo.supersedeConfirmed(confirmation, replacement, now = 30L)
                is SupersessionResult.Committed,
        )
        val before = db.decisionDao().forNote("n1")

        val linkedToPrevious = repo.supersedeConfirmed(
            confirmation,
            replacement.copy(supersedesId = "a"),
            now = 40L,
        )
        assertTrue(linkedToPrevious is SupersessionResult.AlreadyApplied)

        val diverged = repo.supersedeConfirmed(
            confirmation,
            replacement.copy(supersedesId = "c"),
            now = 50L,
        )
        assertTrue(diverged is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.REPLACEMENT_ALREADY_LINKED,
            (diverged as SupersessionResult.Conflict).reason,
        )
        assertEquals(before, db.decisionDao().forNote("n1"))
        assertEquals(30L, db.decisionDao().get("a")!!.updatedAt)
        assertEquals(30L, db.decisionDao().get("b")!!.updatedAt)
    }

    @Test
    fun concorrenciaAParaBAParaCAceitaSomenteUma() = runBlocking {
        val previous = decision(id = "a", createdAt = 10L)
        val b = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        val c = decision(id = "c", createdAt = 20L, updatedAt = 20L)
        seed(previous)
        db.noteDao().upsert(note("n1"))

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    repo.supersedeConfirmed(confirmation(previous, b), b, now = 30L)
                },
                async(Dispatchers.IO) {
                    repo.supersedeConfirmed(confirmation(previous, c), c, now = 30L)
                },
            ).awaitAll()
        }

        assertEquals(1, results.count { it is SupersessionResult.Committed })
        assertEquals(1, results.count { it is SupersessionResult.Conflict })
        assertEquals(DecisionState.SUPERSEDED, db.decisionDao().get("a")!!.toDomain().decisionState)
        val bStored = db.decisionDao().get("b")?.toDomain()
        val cStored = db.decisionDao().get("c")?.toDomain()
        assertEquals(1, listOfNotNull(bStored, cStored).count { it.supersedesId == "a" })
        assertEquals(1, listOfNotNull(bStored, cStored).count { it.decisionState == DecisionState.ACTIVE })
        assertEquals(2, db.decisionDao().countForNote("n1"))
    }

    @Test
    fun rollbackRealEntreInsertEUpdate() = runBlocking {
        val previous = decision(id = "a")
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)
        val failing = DecisionMemoryRepository(
            FailingMarkDao(db.decisionDao()),
            RoomTransactionRunner(db),
        )

        val failure = runCatching {
            failing.supersedeConfirmed(
                confirmation(previous, replacement),
                replacement,
                now = 30L,
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(DecisionState.ACTIVE, db.decisionDao().get("a")!!.toDomain().decisionState)
        assertNull(db.decisionDao().get("b"))
    }

    @Test
    fun colisaoDeChavePrimariaNoRoomReal() = runBlocking {
        val previous = decision(id = "a")
        val existing = decision(id = "b", text = "já existente")
        val incoming = decision(id = "b", createdAt = 20L)
        seed(previous)
        db.decisionDao().upsert(existing.toEntity())

        val result = repo.supersedeConfirmed(
            confirmation(previous, incoming),
            incoming,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Conflict)
        assertEquals(
            SupersessionConflictReason.REPLACEMENT_ID_COLLISION,
            (result as SupersessionResult.Conflict).reason,
        )
        assertEquals(DecisionState.ACTIVE, db.decisionDao().get("a")!!.toDomain().decisionState)
        assertEquals("já existente", db.decisionDao().get("b")!!.text)
    }

    @Test
    fun cadeiaAParaBParaCCOmIntegridadeDeVinculo() = runBlocking {
        val a = decision(id = "a")
        val b = decision(id = "b", createdAt = 20L, updatedAt = 20L)
        val c = decision(id = "c", createdAt = 30L, updatedAt = 30L)
        seed(a)
        repo.supersedeConfirmed(confirmation(a, b), b, now = 30L)
        val bAfter = db.decisionDao().get("b")!!.toDomain()

        val result = repo.supersedeConfirmed(
            confirmation(bAfter, c),
            c,
            now = 40L,
        )

        assertTrue(result is SupersessionResult.Committed)
        assertEquals(DecisionState.SUPERSEDED, db.decisionDao().get("a")!!.toDomain().decisionState)
        assertEquals(DecisionState.SUPERSEDED, db.decisionDao().get("b")!!.toDomain().decisionState)
        assertEquals(DecisionState.ACTIVE, db.decisionDao().get("c")!!.toDomain().decisionState)
        assertEquals("a", db.decisionDao().get("b")!!.supersedesId)
        assertEquals("b", db.decisionDao().get("c")!!.supersedesId)
        assertEquals(40L, db.decisionDao().get("b")!!.updatedAt)
        assertEquals(40L, db.decisionDao().get("c")!!.updatedAt)
    }

    @Test
    fun aplicacaoDaAnteriorPermaneceHistorica() = runBlocking {
        val previous = decision(id = "a", application = ApplicationState.APPLIED)
        val replacement = decision(id = "b", createdAt = 20L)
        seed(previous)

        val result = repo.supersedeConfirmed(
            confirmation(previous, replacement),
            replacement,
            now = 30L,
        )

        assertTrue(result is SupersessionResult.Committed)
        val stored = db.decisionDao().get("a")!!.toDomain()
        assertEquals(DecisionState.SUPERSEDED, stored.decisionState)
        assertEquals(ApplicationState.APPLIED, stored.applicationState)
        assertEquals(11L, stored.appliedAt)
        assertEquals("hash-aplicado", stored.appliedHash)
    }

    @Test
    fun historicoSobreviveAReaberturaDoBanco() = runBlocking {
        val name = "decision-supersession-reopen-test.db"
        ctx.deleteDatabase(name)
        var fileDb: AppDatabase? = null
        try {
            fileDb = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
            fileDb.noteDao().upsert(note("n1"))
            val a = decision(id = "a")
            val b = decision(id = "b", createdAt = 20L, updatedAt = 20L)
            fileDb.decisionDao().upsert(a.toEntity())
            val fileRepo = DecisionMemoryRepository(
                fileDb.decisionDao(),
                RoomTransactionRunner(fileDb),
            )
            assertTrue(
                fileRepo.supersedeConfirmed(
                    confirmation(a, b),
                    b,
                    now = 30L,
                ) is SupersessionResult.Committed,
            )
            fileDb.close()
            fileDb = null

            fileDb = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
            val reopened = fileDb.decisionDao()
            assertEquals(DecisionState.SUPERSEDED, reopened.get("a")!!.toDomain().decisionState)
            assertEquals(DecisionState.ACTIVE, reopened.get("b")!!.toDomain().decisionState)
            assertEquals("a", reopened.get("b")!!.supersedesId)
        } finally {
            fileDb?.close()
            ctx.deleteDatabase(name)
        }
    }

    /**
     * Deixa o insert real acontecer e falha no compare-and-set, forçando o
     * rollback Room da substituta já inserida.
     */
    private class FailingMarkDao(
        private val delegate: DecisionDao,
    ) : DecisionDao {
        override suspend fun forNote(noteId: String): List<DecisionEntity> =
            delegate.forNote(noteId)

        override suspend fun get(id: String): DecisionEntity? = delegate.get(id)

        override suspend fun upsert(record: DecisionEntity) = delegate.upsert(record)

        override suspend fun upsertAll(records: List<DecisionEntity>) =
            delegate.upsertAll(records)

        override suspend fun insertIfAbsent(record: DecisionEntity): Long =
            delegate.insertIfAbsent(record)

        override suspend fun markSupersededIfActive(id: String, updatedAt: Long): Int =
            error("falha simulada no compare-and-set")


        override suspend fun linkReplacementIfUnlinked(
            replacementId: String,
            previousId: String,
            expectedUpdatedAt: Long,
            updatedAt: Long,
        ): Int = delegate.linkReplacementIfUnlinked(
            replacementId,
            previousId,
            expectedUpdatedAt,
            updatedAt,
        )

        override suspend fun deleteById(id: String) = delegate.deleteById(id)

        override suspend fun deleteForNote(noteId: String) = delegate.deleteForNote(noteId)

        override suspend fun countForNote(noteId: String): Int = delegate.countForNote(noteId)
    }
}
