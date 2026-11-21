package com.bettertalker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionPromptMemory
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.loadDecisionPromptMemory
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.speech.SectionRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 1C.2.2B0 — ledger real → bloco canônico, sem legado ressuscitado. */
@RunWith(AndroidJUnit4::class)
class DecisionPromptMemoryRoomTest {

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

    private fun sectionDecision(
        id: String,
        text: String,
        sectionId: String = "s1",
        title: String = "Ponto 1",
        state: DecisionState = DecisionState.ACTIVE,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        updatedAt: Long = 10L,
    ) = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = kind,
        decisionState = state,
        sectionLink = SectionLink.VALID,
        applicationState = ApplicationState.NOT_APPLIED,
        text = text,
        source = DecisionSource.EXPLICIT_USER,
        sectionId = sectionId,
        originalSectionId = sectionId,
        sectionTitleSnapshot = title,
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = if (sectionId == "s1") 1 else 2,
        sectionContentHashSnapshot = "hash-$sectionId",
        originalSectionTitleSnapshot = title,
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = if (sectionId == "s1") 1 else 2,
        originalSectionContentHashSnapshot = "hash-$sectionId",
        createdAt = 10L,
        updatedAt = updatedAt,
        removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
    )

    private fun noteDecision() = DecisionRecord(
        id = "global",
        noteId = "n1",
        scope = DecisionScope.NOTE,
        kind = DecisionKind.CONFIRMED,
        decisionState = DecisionState.ACTIVE,
        sectionLink = SectionLink.NOT_APPLICABLE,
        applicationState = ApplicationState.NOT_APPLIED,
        text = "tom pastoral em toda a nota",
        source = DecisionSource.EXPLICIT_USER,
        createdAt = 10L,
        updatedAt = 10L,
    )

    @Test
    fun ledgerRealSelecionaAtivasESuprimeQuandoHaHistorico() = runBlocking {
        db.noteDao().upsert(note())
        repo.save(
            sectionDecision(
                id = "a",
                text = "começar com uma pergunta",
                state = DecisionState.SUPERSEDED,
                updatedAt = 20L,
            )
        )
        repo.save(
            sectionDecision(
                id = "b",
                text = "começar com uma ilustração",
                updatedAt = 30L,
            )
        )
        repo.save(
            sectionDecision(
                id = "c",
                text = "usar contraste",
                sectionId = "s2",
                title = "Ponto 2",
                updatedAt = 30L,
            )
        )
        repo.save(noteDecision())
        repo.save(
            sectionDecision(
                id = "pref",
                text = "preferência não confirmada",
                kind = DecisionKind.PREFERENCE,
            )
        )

        val result = loadDecisionPromptMemory(
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = false,
            load = { nid -> repo.forNote(nid) },
        )

        assertTrue(result.available)
        assertTrue(result.hasLedgerRecords)
        assertTrue(result.suppressLegacyApproach)
        assertTrue(result.block!!.contains("começar com uma ilustração"))
        assertTrue(result.block!!.contains("tom pastoral em toda a nota"))
        assertFalse(result.block!!.contains("começar com uma pergunta"))
        assertFalse(result.block!!.contains("usar contraste"))
        assertFalse(result.block!!.contains("preferência não confirmada"))
        assertEquals(1, result.noteCount)
        assertEquals(1, result.sectionCount)
    }

    @Test
    fun semRegistrosNaoSuprimeLegado() = runBlocking {
        db.noteDao().upsert(note())
        val result = loadDecisionPromptMemory(
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = false,
            load = { nid -> repo.forNote(nid) },
        )

        assertNull(result.block)
        assertTrue(result.available)
        assertFalse(result.hasLedgerRecords)
        assertFalse(result.suppressLegacyApproach)
        assertEquals(0, result.characters)
    }

    @Test
    fun ledgerIndisponivelNaoInventaMemoriaESuprimeLegado() = runBlocking {
        val result = loadDecisionPromptMemory(
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = false,
            load = { error("banco indisponível") },
        )

        assertNull(result.block)
        assertFalse(result.available)
        assertTrue(result.suppressLegacyApproach)
        assertEquals(0, result.includedCount)
        assertEquals(0, result.characters)
    }
}
