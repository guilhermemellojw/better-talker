package com.bettertalker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionReconciliationHook
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.asReconciliationHook
import com.bettertalker.app.data.copilot.toSectionSnapshot
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.ui.editor.SectionsController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun section(
        id: String = "s1",
        title: String = "Ponto A",
        order: Int = 0,
        html: String = "<p>A</p>",
    ) = SpeechSection(
        id = id,
        noteId = "n1",
        order = order,
        role = SectionRole.BODY,
        title = title,
        minutes = 5,
        contentHtml = html,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        methodPrinciple = null,
        createdAt = 10L,
        updatedAt = 10L,
    )

    private fun decision(
        id: String = "d1",
        section: SpeechSection = section(),
        application: ApplicationState = ApplicationState.NOT_APPLIED,
    ) = section.toSectionSnapshot().let { snapshot ->
        DecisionRecord(
            id = id,
            noteId = section.noteId,
            scope = DecisionScope.SECTION,
            kind = DecisionKind.CONFIRMED,
            decisionState = DecisionState.ACTIVE,
            sectionLink = SectionLink.VALID,
            applicationState = application,
            text = "usar contraste na abertura",
            source = DecisionSource.EXPLICIT_USER,
            sectionId = section.id,
            originalSectionId = section.id,
            sectionTitleSnapshot = snapshot.title,
            sectionRoleSnapshot = snapshot.role,
            sectionOrderSnapshot = snapshot.order,
            sectionContentHashSnapshot = snapshot.contentHash,
            originalSectionTitleSnapshot = snapshot.title,
            originalSectionRoleSnapshot = snapshot.role,
            originalSectionOrderSnapshot = snapshot.order,
            originalSectionContentHashSnapshot = snapshot.contentHash,
            appliedAt = if (application == ApplicationState.NOT_APPLIED) null else 11L,
            appliedHash = if (application == ApplicationState.NOT_APPLIED) null else snapshot.contentHash,
            createdAt = 10L,
            updatedAt = 10L,
        )
    }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        hook: DecisionReconciliationHook = repo.asReconciliationHook(),
    ) = SectionsController(
        noteId = "n1",
        sectionDao = db.speechSectionDao(),
        subPointDao = db.subPointDao(),
        transactionRunner = RoomTransactionRunner(db),
        scope = scope,
        debounceMs = 0,
        clock = { 20L },
        tombstoneDao = db.tombstoneDao(),
        decisionReconciliation = hook,
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
    fun editarSecaoNaoOrfanaDecisoesDeOutrasSecoes() = runBlocking {
        db.noteDao().upsert(note())
        val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
        val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
        db.speechSectionDao().upsertAll(
            listOf(sectionA.toEntity(), sectionB.toEntity()),
        )
        repo.save(decision(id = "dA", section = sectionA, application = ApplicationState.APPLIED))
        repo.save(decision(id = "dB", section = sectionB, application = ApplicationState.APPLIED))
        val bBefore = repo.get("dB")!!
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 2 }
            controller.onSectionContent("s1", "<p>A atualizado</p>")
            controller.awaitSaves()

            val aAfter = repo.get("dA")!!
            val bAfter = repo.get("dB")!!

            assertEquals(DecisionState.ACTIVE, aAfter.decisionState)
            assertEquals(SectionLink.VALID, aAfter.sectionLink)
            assertEquals(
                sectionA.copy(contentHtml = "<p>A atualizado</p>").toSectionSnapshot().contentHash,
                aAfter.sectionContentHashSnapshot,
            )
            assertEquals(ApplicationState.STALE, aAfter.applicationState)

            assertEquals(DecisionState.ACTIVE, bAfter.decisionState)
            assertEquals(SectionLink.VALID, bAfter.sectionLink)
            assertEquals("s2", bAfter.sectionId)
            assertEquals("s2", bAfter.originalSectionId)
            assertEquals(bBefore, bAfter)
        } finally {
            controller.close()
        }
    }

    @Test
    fun alterarTituloNaoOrfanaDecisoesDeOutrasSecoes() = runBlocking {
        db.noteDao().upsert(note())
        val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
        val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
        db.speechSectionDao().upsertAll(listOf(sectionA.toEntity(), sectionB.toEntity()))
        repo.save(decision(id = "dA", section = sectionA))
        repo.save(decision(id = "dB", section = sectionB))
        val bBefore = repo.get("dB")!!
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 2 }
            controller.onSectionTitle("s1", "Ponto A renomeado")
            controller.awaitSaves()

            val aAfter = repo.get("dA")!!
            assertEquals(SectionLink.VALID, aAfter.sectionLink)
            assertEquals("Ponto A renomeado", aAfter.sectionTitleSnapshot)
            assertEquals(bBefore, repo.get("dB"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun editarSecaoIntermediariaPreservaDecisoesDasLaterais() = runBlocking {
        db.noteDao().upsert(note())
        val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
        val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
        val sectionC = section(id = "s3", title = "Ponto C", order = 2, html = "<p>C</p>")
        db.speechSectionDao().upsertAll(
            listOf(sectionA.toEntity(), sectionB.toEntity(), sectionC.toEntity()),
        )
        repo.save(decision(id = "dA", section = sectionA))
        repo.save(decision(id = "dB", section = sectionB))
        repo.save(decision(id = "dC", section = sectionC))
        val aBefore = repo.get("dA")!!
        val cBefore = repo.get("dC")!!
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 3 }
            controller.onSectionContent("s2", "<p>B atualizado</p>")
            controller.awaitSaves()

            val bAfter = repo.get("dB")!!
            assertEquals(SectionLink.VALID, bAfter.sectionLink)
            assertEquals(
                sectionB.copy(contentHtml = "<p>B atualizado</p>").toSectionSnapshot().contentHash,
                bAfter.sectionContentHashSnapshot,
            )
            assertEquals(aBefore, repo.get("dA"))
            assertEquals(cBefore, repo.get("dC"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun autosavesDeDuasSecoesNaoOrfanamNenhuma() = runBlocking {
        db.noteDao().upsert(note())
        val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
        val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
        db.speechSectionDao().upsertAll(listOf(sectionA.toEntity(), sectionB.toEntity()))
        repo.save(decision(id = "dA", section = sectionA))
        repo.save(decision(id = "dB", section = sectionB))
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 2 }
            controller.onSectionContent("s1", "<p>A atualizado</p>")
            controller.onSectionContent("s2", "<p>B atualizado</p>")
            controller.awaitSaves()

            val aAfter = repo.get("dA")!!
            val bAfter = repo.get("dB")!!
            assertEquals(SectionLink.VALID, aAfter.sectionLink)
            assertEquals(SectionLink.VALID, bAfter.sectionLink)
            assertEquals("s1", aAfter.sectionId)
            assertEquals("s2", bAfter.sectionId)
            assertEquals(
                sectionA.copy(contentHtml = "<p>A atualizado</p>").toSectionSnapshot().contentHash,
                aAfter.sectionContentHashSnapshot,
            )
            assertEquals(
                sectionB.copy(contentHtml = "<p>B atualizado</p>").toSectionSnapshot().contentHash,
                bAfter.sectionContentHashSnapshot,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun editarUmaSecaoEExcluirOutraMantemDecisaoDaEditada() = runBlocking {
        db.noteDao().upsert(note())
        val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
        val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
        db.speechSectionDao().upsertAll(listOf(sectionA.toEntity(), sectionB.toEntity()))
        repo.save(decision(id = "dA", section = sectionA))
        repo.save(decision(id = "dB", section = sectionB))
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 2 }
            controller.onSectionContent("s1", "<p>A atualizado</p>")
            controller.removeSection("s2")
            controller.awaitSaves()

            val aAfter = repo.get("dA")!!
            assertEquals(SectionLink.VALID, aAfter.sectionLink)
            assertEquals(
                sectionA.copy(contentHtml = "<p>A atualizado</p>").toSectionSnapshot().contentHash,
                aAfter.sectionContentHashSnapshot,
            )
            assertEquals(SectionLink.ORPHANED, repo.get("dB")!!.sectionLink)
        } finally {
            controller.close()
        }
    }

    @Test
    fun repetirMesmoConteudoNaoAtualizaDecisoes() = runBlocking {
        db.noteDao().upsert(note())
        val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
        val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
        db.speechSectionDao().upsertAll(listOf(sectionA.toEntity(), sectionB.toEntity()))
        repo.save(decision(id = "dA", section = sectionA))
        repo.save(decision(id = "dB", section = sectionB))
        val controller = controller(this)
        try {
            controller.sections.first { it.size == 2 }
            controller.onSectionContent("s1", "<p>A atualizado</p>")
            controller.awaitSaves()
            val aFirst = repo.get("dA")!!
            val bFirst = repo.get("dB")!!

            controller.onSectionContent("s1", "<p>A atualizado</p>")
            controller.awaitSaves()

            assertEquals(aFirst, repo.get("dA"))
            assertEquals(bFirst, repo.get("dB"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun falhaNaReconciliacaoDesfazAutosave() {
        val failures = mutableListOf<Throwable>()
        runBlocking(CoroutineExceptionHandler { _, t -> failures += t }) {
            db.noteDao().upsert(note())
            val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
            val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
            db.speechSectionDao().upsertAll(listOf(sectionA.toEntity(), sectionB.toEntity()))
            repo.save(decision(id = "dA", section = sectionA))
            val aBefore = repo.get("dA")!!
            val controller = controller(
                this,
                DecisionReconciliationHook { _, _, _, _, _ -> error("falha simulada") },
            )
            try {
                controller.sections.first { it.size == 2 }
                controller.onSectionContent("s1", "<p>não deve persistir</p>")
                controller.awaitSaves()

                assertEquals(1, failures.size)
                assertEquals("<p>A</p>", db.speechSectionDao().get("s1")!!.contentHtml)
                assertEquals(aBefore, repo.get("dA"))
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun falhaNaReconciliacaoDesfazRemocaoDeSecao() {
        val failures = AtomicInteger(0)
        val controllerScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, _ -> failures.incrementAndGet() },
        )
        try {
            runBlocking {
                db.noteDao().upsert(note())
                val sectionA = section(id = "s1", title = "Ponto A", order = 0, html = "<p>A</p>")
                val sectionB = section(id = "s2", title = "Ponto B", order = 1, html = "<p>B</p>")
                db.speechSectionDao().upsertAll(listOf(sectionA.toEntity(), sectionB.toEntity()))
                repo.save(decision(id = "dB", section = sectionB))
                val bBefore = repo.get("dB")!!
                val controller = controller(
                    controllerScope,
                    DecisionReconciliationHook { _, _, _, _, _ -> error("falha simulada") },
                )
                try {
                    controller.sections.first { it.size == 2 }
                    controller.removeSection("s2")
                    controller.awaitSaves()

                    assertEquals(1, failures.get())
                    assertNotNull(db.speechSectionDao().get("s2"))
                    assertEquals(bBefore, repo.get("dB"))
                } finally {
                    controller.close()
                }
            }
        } finally {
            controllerScope.cancel()
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
