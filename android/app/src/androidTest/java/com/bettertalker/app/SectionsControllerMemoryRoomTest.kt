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
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.asReconciliationHook
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.ui.editor.SectionsController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 1C.2.2A — fio real: SectionsController → reconciler → Room. */
@RunWith(AndroidJUnit4::class)
class SectionsControllerMemoryRoomTest {

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

    private fun note() = NoteEntity(
        id = "n1",
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

    private fun section(html: String = "<p>A</p>") = SpeechSection(
        id = "s1",
        noteId = "n1",
        order = 0,
        role = SectionRole.BODY,
        title = "Ponto A",
        minutes = 5,
        contentHtml = html,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        methodPrinciple = null,
        createdAt = 10L,
        updatedAt = 10L,
    )

    private fun decision(
        application: ApplicationState = ApplicationState.NOT_APPLIED,
    ) = DecisionRecord(
        id = "d1",
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = DecisionState.ACTIVE,
        sectionLink = SectionLink.VALID,
        applicationState = application,
        text = "usar contraste na abertura",
        source = DecisionSource.EXPLICIT_USER,
        sectionId = "s1",
        originalSectionId = "s1",
        sectionTitleSnapshot = "Ponto A",
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = 0,
        sectionContentHashSnapshot = "hash-a",
        originalSectionTitleSnapshot = "Ponto A",
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = 0,
        originalSectionContentHashSnapshot = "hash-a",
        appliedAt = if (application == ApplicationState.NOT_APPLIED) null else 11L,
        appliedHash = if (application == ApplicationState.NOT_APPLIED) null else "hash-aplicado",
        createdAt = 10L,
        updatedAt = 10L,
    )

    private fun controller(scope: kotlinx.coroutines.CoroutineScope) =
        SectionsController(
            noteId = "n1",
            sectionDao = db.speechSectionDao(),
            subPointDao = db.subPointDao(),
            transactionRunner = RoomTransactionRunner(db),
            scope = scope,
            debounceMs = 0,
            clock = { 20L },
            tombstoneDao = db.tombstoneDao(),
            decisionReconciliation = repo.asReconciliationHook(),
        )

    @Test
    fun removerEDesfazerSecaoReconciliaNoRoomReal() = runBlocking {
        db.noteDao().upsert(note())
        db.speechSectionDao().upsert(section().toEntity())
        repo.save(decision())
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 1 }
            controller.removeSection("s1")
            controller.awaitSaves()
            assertEquals(SectionLink.ORPHANED, repo.get("d1")!!.sectionLink)

            controller.undoRemoveSection()
            controller.awaitSaves()
            assertEquals(SectionLink.VALID, repo.get("d1")!!.sectionLink)
        } finally {
            controller.close()
        }
    }

    @Test
    fun editarConteudoMarcaAplicacaoStaleNoRoomReal() = runBlocking {
        db.noteDao().upsert(note())
        db.speechSectionDao().upsert(section().toEntity())
        repo.save(decision(application = ApplicationState.APPLIED))
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 1 }
            controller.onSectionContent("s1", "<p>conteúdo novo</p>")
            controller.awaitSaves()
            assertEquals(
                ApplicationState.STALE,
                repo.get("d1")!!.applicationState,
            )
        } finally {
            controller.close()
        }
    }
}
