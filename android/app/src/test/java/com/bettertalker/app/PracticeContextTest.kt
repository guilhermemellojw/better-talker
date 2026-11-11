package com.bettertalker.app

import com.bettertalker.app.data.copilot.PracticePresentState
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.copilot.formatPracticeElapsed
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.toJson
import com.bettertalker.app.ui.copilot.buildPracticeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fatia 5 — snapshot de prática (Treino/Palco). Puro: sem Room/Compose/rede.
 */
class PracticeContextTest {

    private fun note(type: DiscourseType) = NoteEntity(
        id = "note",
        title = "Nota",
        mdText = "",
        plainText = "",
        folderId = null,
        colorArgb = 0,
        pinned = false,
        trashed = false,
        createdAt = 0,
        updatedAt = 0,
        discourseType = type.name,
    )

    private fun section(
        id: String,
        order: Int,
        role: SectionRole,
        title: String,
        html: String = "",
        tribunaJson: String? = null,
    ) = SpeechSection(
        id = id,
        noteId = "note",
        order = order,
        role = role,
        title = title,
        minutes = 4,
        contentHtml = html,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        methodPrinciple = null,
        tribunaJson = tribunaJson,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun sub(id: String, sectionId: String, html: String) = SubPoint(
        id = id,
        sectionId = sectionId,
        order = 0,
        outlineText = "",
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        instruction = null,
        developedHtml = html,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun formataTempoDaSessao() {
        assertEquals("0:00", formatPracticeElapsed(0))
        assertEquals("1:05", formatPracticeElapsed(65))
        assertEquals("1:02:05", formatPracticeElapsed(3725))
    }

    @Test
    fun snapshotTemLinhaEPromptHonestos() {
        val s = PracticeSnapshot(
            noteId = "note",
            hasTrainingText = true,
            trainPrepSummary = "inteiro · análise · cronômetro",
            presentState = PracticePresentState.READY,
            presentLabel = "Mapa mental pronto",
            savedSectionIndex = 1,
            savedSectionTitle = "Ponto 1",
            savedElapsedSec = 312,
        )
        assertTrue(s.contextLine, s.contextLine.contains("Treino: pronto"))
        assertTrue(s.contextLine, s.contextLine.contains("preparação: inteiro"))
        assertTrue(s.contextLine, s.contextLine.contains("Palco: Mapa mental pronto"))
        assertTrue(s.contextLine, s.contextLine.contains("continuar da seção 2 · 5:12 (Ponto 1)"))
        assertTrue(s.promptBlock, s.promptBlock.contains("não é resultado de treino"))
        assertTrue(s.promptBlock, s.promptBlock.contains("sessão salva"))
        assertTrue(s.promptBlock, s.promptBlock.contains("5:12"))
    }

    @Test
    fun demoComRoteiroFicaProntaParaOPalco() {
        val sections = listOf(
            section(
                "i", 0, SectionRole.INTRO, "Introdução",
                "<p><b>Publicador:</b> Olá, tudo bem?</p>",
            ),
            section(
                "b1", 1, SectionRole.BODY, "Ponto 1",
                "<p><b>Morador:</b> Tudo bem.</p>",
            ),
        )
        val s = buildPracticeSnapshot(
            noteId = "note",
            note = note(DiscourseType.MINISTRY_PART),
            sections = sections,
            subPointsBySection = emptyMap(),
            trainPrepRaw = "WHOLE|A|1",
            presentSessionRaw = "1|65|123",
        )
        assertTrue(s.hasTrainingText)
        assertEquals(PracticePresentState.READY, s.presentState)
        assertEquals("Roteiro pronto", s.presentLabel)
        assertEquals("inteiro · análise · cronômetro", s.trainPrepSummary)
        assertEquals(1, s.savedSectionIndex)
        assertEquals("Ponto 1", s.savedSectionTitle)
        assertEquals(65L, s.savedElapsedSec)
    }

    @Test
    fun s34ContaSubpontoComoTrechoPreenchido() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução", "<p>texto</p>"),
            section("b1", 1, SectionRole.BODY, "Ponto 1"),
            section("c", 2, SectionRole.CONCLUSION, "Conclusão", "<p>texto</p>"),
        )
        val s = buildPracticeSnapshot(
            noteId = "note",
            note = note(DiscourseType.S34_DISCOURSE),
            sections = sections,
            subPointsBySection = mapOf("b1" to listOf(sub("sp1", "b1", "<p>texto</p>"))),
            trainPrepRaw = null,
            presentSessionRaw = null,
        )
        assertTrue(s.hasTrainingText)
        assertEquals(true, s.hasTextBySectionId["b1"])
        assertEquals(PracticePresentState.MISSING, s.presentState)
        assertEquals("Falta gerar o mapa mental", s.presentLabel)
    }

    @Test
    fun tribunaProntaMarcaPalco() {
        val capsule = TribunaCapsule(
            sectionId = "b1",
            mainIdea = "Jeová organiza os céus.",
            anchorPhrase = "Ele valoriza muito a ordem",
        )
        val sections = listOf(
            section(
                "b1", 0, SectionRole.BODY, "Ponto 1",
                tribunaJson = capsule.toJson(),
            ),
        )
        val s = buildPracticeSnapshot(
            noteId = "note",
            note = note(DiscourseType.S34_DISCOURSE),
            sections = sections,
            subPointsBySection = emptyMap(),
            trainPrepRaw = null,
            presentSessionRaw = null,
        )
        assertEquals(PracticePresentState.READY, s.presentState)
        assertEquals("Mapa mental pronto", s.presentLabel)
    }
}
