package com.bettertalker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.DecisionDao
import com.bettertalker.app.data.db.DecisionEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.SectionPlan
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.ui.copilot.StructureAcceptRequest
import com.bettertalker.app.ui.copilot.StructureAcceptResult
import com.bettertalker.app.ui.copilot.StructureAcceptService
import com.bettertalker.app.ui.copilot.buildStructureAcceptDecision
import com.bettertalker.app.ui.copilot.sectionProposalBlock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 1C.2.1B1 — aceite estrutural + decisão na mesma transação Room real. */
@RunWith(AndroidJUnit4::class)
class StructureAcceptMemoryRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var service: StructureAcceptService

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = serviceWith(db.decisionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun serviceWith(decisionDao: DecisionDao): StructureAcceptService {
        val runner = RoomTransactionRunner(db)
        return StructureAcceptService(
            transactionRunner = runner,
            sectionDao = db.speechSectionDao(),
            decisionMemory = DecisionMemoryRepository(decisionDao, runner),
        )
    }

    private fun note(id: String) = NoteEntity(
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

    private fun section(
        id: String,
        noteId: String,
        title: String = "Fé que age",
        contentHtml: String = "<p>texto atual da seção</p>",
    ) = SpeechSectionEntity(
        id = id,
        noteId = noteId,
        order = 0,
        role = SectionRole.BODY.name,
        title = title,
        minutes = 5,
        contentHtml = contentHtml,
        bibleRefsJson = "[]",
        publicationRefsJson = "[]",
        methodPrinciple = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun sectionDomain(
        id: String,
        noteId: String,
        title: String = "Fé que age",
        contentHtml: String = "<p>texto atual da seção</p>",
    ) = SpeechSection(
        id = id,
        noteId = noteId,
        order = 0,
        role = SectionRole.BODY,
        title = title,
        minutes = 5,
        contentHtml = contentHtml,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        methodPrinciple = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun plan() = SectionPlan(
        title = "Fé que age",
        minutes = 7,
        mainIdea = "Mostrar que o desejo de viver é legítimo.",
        bibleRefs = listOf("Ec 3:11"),
    )

    private fun proposal() = OutlineProposal(
        id = "outline-1",
        angle = OutlineAngle.PRACTICAL,
        audience = Audience.GENERAL,
        title = "Discurso",
        summary = "Resumo da proposta.",
        totalMinutes = 10,
        sections = listOf(plan()),
    )

    private fun request(
        proposalId: String = "sprop-1",
        noteId: String = "n1",
        sectionId: String = "s1",
        title: String = "Fé que age",
        contentHtml: String = "<p>texto atual da seção</p>",
    ) = StructureAcceptRequest(
        proposalId = proposalId,
        noteId = noteId,
        sectionId = sectionId,
        proposal = proposal(),
        plan = plan(),
        summary = "Resumo da proposta.",
        sectionBaseHash = hashText(sectionProposalBlock(title, contentHtml)),
        allowEmptyRefs = false,
    )

    @Test
    fun aceiteValidoPersisteDecisaoESecaoNaMesmaTransacao() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.speechSectionDao().upsert(section("s1", "n1"))

        val result = service.accept(request(), now = 20L)

        assertTrue(result is StructureAcceptResult.Applied)
        val decision = (result as StructureAcceptResult.Applied).decision
        assertEquals(decision, db.decisionDao().get(decision.id)?.toDomain())
        assertEquals(DecisionSource.STRUCTURE_ACCEPT, decision.source)
        assertEquals(ApplicationState.NOT_APPLIED, decision.applicationState)
        assertEquals("n1", decision.noteId)
        assertEquals("s1", decision.sectionId)
        assertEquals("s1", decision.originalSectionId)
        assertEquals("sprop-1", decision.sourceProposalId)
        assertEquals(20L, decision.createdAt)
        assertEquals(20L, decision.updatedAt)

        val stored = db.speechSectionDao().get("s1")!!
        assertEquals(7, stored.minutes)
        assertEquals(20L, stored.updatedAt)
        assertTrue(stored.agreedApproach!!.contains("Resumo da proposta."))
    }

    @Test
    fun mesmoAceiteDuasVezesNaoDuplicaNemReaplica() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.speechSectionDao().upsert(section("s1", "n1"))

        val first = service.accept(request(), now = 20L)
        val second = service.accept(request(), now = 30L)

        assertTrue(first is StructureAcceptResult.Applied)
        assertTrue(second is StructureAcceptResult.AlreadyApplied)
        assertEquals(1, db.decisionDao().countForNote("n1"))
        assertEquals(20L, db.decisionDao().forNote("n1").single().createdAt)
        assertEquals(20L, db.speechSectionDao().get("s1")!!.updatedAt)
    }

    @Test
    fun repeticaoAposEdicaoPosteriorNaoAvaliaStaleNemAlteraNada() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.speechSectionDao().upsert(section("s1", "n1"))
        val first = service.accept(request(), now = 20L) as StructureAcceptResult.Applied

        val edited = db.speechSectionDao().get("s1")!!.copy(
            contentHtml = "<p>editado depois</p>",
            updatedAt = 25L,
        )
        db.speechSectionDao().upsert(edited)

        // O request ainda carrega o hash antigo; o evento já persistido vence.
        val repeated = service.accept(request(), now = 30L)

        assertTrue(repeated is StructureAcceptResult.AlreadyApplied)
        assertEquals(first.decision, (repeated as StructureAcceptResult.AlreadyApplied).decision)
        assertEquals(1, db.decisionDao().countForNote("n1"))
        assertEquals(20L, db.decisionDao().forNote("n1").single().updatedAt)
        val stored = db.speechSectionDao().get("s1")!!
        assertEquals("<p>editado depois</p>", stored.contentHtml)
        assertEquals(25L, stored.updatedAt)
    }

    @Test
    fun colisaoDeIdentidadeEntreNotasFalhaSemAlterarNada() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.noteDao().upsert(note("n2"))
        db.speechSectionDao().upsert(section("s1", "n1"))
        val otherNoteSection = sectionDomain(id = "s1", noteId = "n2")
        val colliding = buildStructureAcceptDecision(
            proposalId = "sprop-1",
            current = otherNoteSection,
            updated = otherNoteSection,
            summary = "Outra proposta",
            plan = plan(),
            now = 10L,
        )
        db.decisionDao().upsert(colliding.toEntity())

        val error = runCatching { service.accept(request(), now = 20L) }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(colliding, db.decisionDao().get(colliding.id)?.toDomain())
        val stored = db.speechSectionDao().get("s1")!!
        assertEquals(5, stored.minutes)
        assertEquals(1L, stored.updatedAt)
        assertNull(stored.agreedApproach)
    }

    @Test
    fun falhaNaDecisaoFazRollbackDaSecaoNoRoom() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.speechSectionDao().upsert(section("s1", "n1"))
        val failing = serviceWith(FailingDecisionDao(db.decisionDao()))

        val error = runCatching { failing.accept(request(), now = 20L) }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(0, db.decisionDao().countForNote("n1"))
        val stored = db.speechSectionDao().get("s1")!!
        assertEquals(5, stored.minutes)
        assertEquals(1L, stored.updatedAt)
        assertNull(stored.agreedApproach)
    }

    @Test
    fun aceiteDeUmaNotaNaoAlteraDecisaoDeOutraNota() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.noteDao().upsert(note("n2"))
        db.speechSectionDao().upsert(section("s1", "n1"))
        db.speechSectionDao().upsert(section("s2", "n2"))

        val n2 = service.accept(
            request(proposalId = "sprop-2", noteId = "n2", sectionId = "s2"),
            now = 20L,
        ) as StructureAcceptResult.Applied
        val n1 = service.accept(
            request(proposalId = "sprop-1", sectionId = "s1"),
            now = 30L,
        ) as StructureAcceptResult.Applied

        assertEquals(2, db.decisionDao().countForNote("n1") + db.decisionDao().countForNote("n2"))
        assertEquals("n2", n2.decision.noteId)
        assertEquals("n1", n1.decision.noteId)
        assertEquals(
            n2.decision,
            db.decisionDao().get(n2.decision.id)?.toDomain(),
        )
        assertEquals(20L, n2.decision.updatedAt)
    }

    private class FailingDecisionDao(
        private val delegate: DecisionDao,
    ) : DecisionDao {
        override suspend fun forNote(noteId: String): List<DecisionEntity> =
            delegate.forNote(noteId)

        override suspend fun get(id: String): DecisionEntity? = delegate.get(id)

        override suspend fun upsert(record: DecisionEntity) {
            error("falha simulada na decisão")
        }

        override suspend fun upsertAll(records: List<DecisionEntity>) {
            error("falha simulada na decisão")
        }

        override suspend fun insertIfAbsent(record: DecisionEntity): Long =
            delegate.insertIfAbsent(record)

        override suspend fun markSupersededIfActive(id: String, updatedAt: Long): Int =
            delegate.markSupersededIfActive(id, updatedAt)

        override suspend fun deleteById(id: String) = delegate.deleteById(id)

        override suspend fun deleteForNote(noteId: String) = delegate.deleteForNote(noteId)

        override suspend fun countForNote(noteId: String): Int = delegate.countForNote(noteId)
    }
}
