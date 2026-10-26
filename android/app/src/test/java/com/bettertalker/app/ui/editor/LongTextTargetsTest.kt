package com.bettertalker.app.ui.editor

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bloco de geração longa: seções de 20+ min viram sub-pontos; o "texto total"
 * vira uma fila na ordem da nota. Puro/testável.
 */
class LongTextTargetsTest {

    private fun section(
        id: String,
        order: Int,
        role: SectionRole = SectionRole.BODY,
        minutes: Int = 5,
    ) = SpeechSection(
        id = id, noteId = "n1", order = order, role = role, title = "T$id",
        minutes = minutes, contentHtml = "", bibleRefs = emptyList(),
        publicationRefs = emptyList(), methodPrinciple = null,
        createdAt = 0, updatedAt = 0,
    )

    private fun sub(id: String, sectionId: String, order: Int) = SubPoint(
        id = id, sectionId = sectionId, order = order, outlineText = "o",
        bibleRefs = emptyList(), publicationRefs = emptyList(), instruction = null,
        developedHtml = "", createdAt = 0, updatedAt = 0,
    )

    private fun ui(
        section: SpeechSection,
        subPoints: List<SubPoint> = emptyList(),
    ) = SectionUiState(section, subPoints)

    @Test
    fun secaoCurta_continuaSecao() {
        val state = ui(section("b", 1, minutes = 10), listOf(sub("sp1", "b", 0)))
        assertEquals(
            DraftTarget.Section("b"),
            preferredDraftTarget(DraftTarget.Section("b"), listOf(state)),
        )
    }

    @Test
    fun secao20min_comSubPontos_preferePrimeiroSubPonto() {
        val state = ui(
            section("b", 1, minutes = 20),
            listOf(sub("sp2", "b", 1), sub("sp1", "b", 0)),
        )
        assertEquals(
            DraftTarget.SubPoint("b", "sp1"),
            preferredDraftTarget(DraftTarget.Section("b"), listOf(state)),
        )
    }

    @Test
    fun secao20min_semSubPontos_continuaSecao() {
        val state = ui(section("b", 1, minutes = 20))
        assertEquals(
            DraftTarget.Section("b"),
            preferredDraftTarget(DraftTarget.Section("b"), listOf(state)),
        )
    }

    @Test
    fun textoTotal_ordemComIntroBodiesConclusao() {
        val sections = listOf(
            ui(section("c", 2, SectionRole.CONCLUSION, 1)),
            ui(section("b1", 1, minutes = 5)),
            ui(section("i", 0, SectionRole.INTRO, 1)),
        )
        assertEquals(
            listOf(
                DraftTarget.Section("i"),
                DraftTarget.Section("b1"),
                DraftTarget.Section("c"),
            ),
            totalTextTargets(sections),
        )
    }

    @Test
    fun textoTotal_secaoLonga_dividePorSubPonto() {
        val long = ui(
            section("b", 1, minutes = 20),
            listOf(sub("sp2", "b", 1), sub("sp1", "b", 0)),
        )
        val sections = listOf(
            ui(section("i", 0, SectionRole.INTRO, 1)),
            long,
            ui(section("c", 2, SectionRole.CONCLUSION, 1)),
        )
        assertEquals(
            listOf(
                DraftTarget.Section("i"),
                DraftTarget.SubPoint("b", "sp1"),
                DraftTarget.SubPoint("b", "sp2"),
                DraftTarget.Section("c"),
            ),
            totalTextTargets(sections),
        )
    }
}
