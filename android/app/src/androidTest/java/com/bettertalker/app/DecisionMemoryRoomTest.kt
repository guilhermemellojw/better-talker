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
import com.bettertalker.app.data.copilot.ReconcileOperation
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.SectionSnapshot
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1C.2.1A2 — integração real com Room in-memory.
 *
 * Confirma o schema v27, o round-trip do repositório e as políticas de FK:
 * nota apagada em cascata; seção apagada NÃO apaga decisão; lixeira preserva.
 */
@RunWith(AndroidJUnit4::class)
class DecisionMemoryRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DecisionMemoryRepository

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
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
        createdAt = 10L,
        updatedAt = 10L,
    )

    private fun section(id: String = "s1", noteId: String = "n1") = SpeechSectionEntity(
        id = id,
        noteId = noteId,
        order = 1,
        role = SectionRole.BODY.name,
        title = "Ponto A",
        minutes = 5,
        contentHtml = "<p>A</p>",
        bibleRefsJson = "[]",
        publicationRefsJson = "[]",
        methodPrinciple = null,
        createdAt = 10L,
        updatedAt = 10L,
    )

    private fun decision(
        id: String = "d1",
        noteId: String = "n1",
        link: SectionLink = SectionLink.VALID,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        candidateSectionId: String? = null,
        supersedesId: String? = null,
    ) = DecisionRecord(
        id = id,
        noteId = noteId,
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = DecisionState.ACTIVE,
        sectionLink = link,
        applicationState = application,
        text = "usar contraste na abertura",
        source = DecisionSource.EXPLICIT_USER,
        sectionId = "s2",
        originalSectionId = "s1",
        candidateSectionId = candidateSectionId,
        sectionTitleSnapshot = "Ponto B",
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = 2,
        sectionContentHashSnapshot = "hash-b",
        originalSectionTitleSnapshot = "Ponto A",
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = 1,
        originalSectionContentHashSnapshot = "hash-a",
        supersedesId = supersedesId,
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 11L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) "hash-aplicado" else null,
        createdAt = 10L,
        updatedAt = 10L,
    )

    private fun snap(
        id: String = "s2",
        title: String = "Ponto B",
        order: Int = 2,
        hash: String = "hash-b",
    ) = SectionSnapshot(
        id = id,
        noteId = "n1",
        role = SectionRole.BODY,
        title = title,
        order = order,
        contentHash = hash,
    )

    @Test
    fun roundTripRealPreservaSnapshotsETimestamps() = runBlocking {
        db.noteDao().upsert(note())
        val original = decision(application = ApplicationState.STALE)
        repo.save(original)

        val loaded = repo.get("d1")
        assertEquals(original, loaded)
        assertEquals("Ponto B", loaded!!.sectionTitleSnapshot)
        assertEquals("Ponto A", loaded.originalSectionTitleSnapshot)
        assertEquals(10L, loaded.createdAt)
        assertEquals(listOf(original), repo.forNote("n1"))
    }

    @Test
    fun excluirNotaCascateiaDecisoes() = runBlocking {
        db.noteDao().upsert(note())
        repo.save(decision())
        assertNotNull(repo.get("d1"))

        db.noteDao().delete("n1")

        assertNull(repo.get("d1"))
        assertEquals(0, db.decisionDao().countForNote("n1"))
    }

    @Test
    fun excluirSecaoNaoRemoveDecisao() = runBlocking {
        db.noteDao().upsert(note())
        db.speechSectionDao().upsert(section("s2"))
        repo.save(decision())

        db.speechSectionDao().delete(section("s2"))

        val stored = repo.get("d1")
        assertNotNull(stored)
        assertEquals("s2", stored!!.sectionId)
        assertEquals(SectionLink.VALID, stored.sectionLink)
    }

    @Test
    fun notaNaLixeiraPreservaDecisoes() = runBlocking {
        db.noteDao().upsert(note())
        repo.save(decision())

        db.noteDao().trash("n1", now = 20L)

        assertNotNull(repo.get("d1"))
    }

    @Test
    fun reconcilePersisteSomenteMudancas() = runBlocking {
        db.noteDao().upsert(note())
        repo.save(decision())

        val report = repo.reconcile(
            noteId = "n1",
            oldSections = listOf(snap()),
            newSections = listOf(snap(title = "Ponto C", order = 3, hash = "hash-c")),
            operation = ReconcileOperation.REORDER,
            now = 30L,
        )

        val stored = repo.get("d1")!!
        assertEquals("Ponto C", stored.sectionTitleSnapshot)
        assertEquals("Ponto A", stored.originalSectionTitleSnapshot)
        assertEquals(30L, stored.updatedAt)
        assertEquals(stored, report.decisions.single())
    }

    @Test
    fun supersedePersisteParEmUmaTransacao() = runBlocking {
        db.noteDao().upsert(note())
        repo.save(decision())
        val replacement = decision(id = "d2", supersedesId = "d1")

        val pair = repo.supersede("d1", replacement, now = 30L)!!

        assertEquals(DecisionState.SUPERSEDED, repo.get("d1")!!.decisionState)
        assertEquals("d1", repo.get("d2")!!.supersedesId)
        assertEquals(2, db.decisionDao().countForNote("n1"))
        assertEquals(30L, pair.previous.updatedAt)
    }

    @Test
    fun replaceForNoteEmRoomSubstituiSomenteANota() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.noteDao().upsert(note("n2"))
        repo.save(decision())
        repo.save(decision(id = "d9", noteId = "n2"))

        repo.replaceForNote("n1", emptyList())

        assertEquals(0, db.decisionDao().countForNote("n1"))
        assertEquals(1, db.decisionDao().countForNote("n2"))
    }

    @Test
    fun replaceForNoteEmRoomRejeitaColisaoDeIdComOutraNota() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.noteDao().upsert(note("n2"))
        repo.save(decision(id = "d1"))
        repo.save(decision(id = "d2", noteId = "n2"))

        val error = runCatching {
            repo.replaceForNote(
                "n1",
                listOf(
                    decision(id = "d3"),
                    decision(id = "d2", noteId = "n1"),
                ),
            )
        }.exceptionOrNull()

        assertNotNull("a colisão entre notas deve falhar", error)
        // Nenhuma nota foi alterada: n1 mantém d1 e n2 mantém d2.
        assertEquals(1, db.decisionDao().countForNote("n1"))
        assertEquals(1, db.decisionDao().countForNote("n2"))
        assertEquals("n1", repo.get("d1")!!.noteId)
        assertEquals("n2", repo.get("d2")!!.noteId)
    }
}
