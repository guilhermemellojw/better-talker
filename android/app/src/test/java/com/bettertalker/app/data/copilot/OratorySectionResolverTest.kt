package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OratorySectionResolverTest {

    private val intro = NoteSectionRef("n-intro", 0, SectionRole.INTRO, "Introdução")
    private val point1 = NoteSectionRef("n-p1", 1, SectionRole.BODY, "Ponto 1")
    private val point2 = NoteSectionRef("n-p2", 2, SectionRole.BODY, "Ponto 2")
    private val conclusion = NoteSectionRef("n-end", 3, SectionRole.CONCLUSION, "Conclusão")
    private val notes = listOf(intro, point1, point2, conclusion)

    private val s34Point1 = S34SectionRef("sec-1", 1, "Ponto 1")
    private val s34Point2 = S34SectionRef("sec-2", 2, "Ponto 2")
    private val s34 = listOf(s34Point1, s34Point2)

    @Test
    fun sec2MapeiaPonto2PorTitulo() {
        assertEquals(
            point2,
            resolveOratoryNoteSection(
                sectionId = "sec-2",
                mode = OratoryGeneration.Mode.DEVELOPMENT,
                noteSections = notes,
                s34Sections = s34,
            ),
        )
    }

    @Test
    fun tituloEditadoUsaOrdemDoPonto() {
        val edited = notes.map {
            if (it.id == point2.id) it.copy(title = "Ponto 2 — examinar o coração") else it
        }
        assertEquals(
            "n-p2",
            resolveOratoryNoteSection(
                sectionId = "sec-2",
                mode = OratoryGeneration.Mode.DEVELOPMENT,
                noteSections = edited,
                s34Sections = s34,
            )?.id,
        )
    }

    @Test
    fun introducaoEConclusaoResolvemPorPapel() {
        assertEquals(
            intro,
            resolveOratoryNoteSection(
                sectionId = "sec-1",
                mode = OratoryGeneration.Mode.INTRODUCTION,
                noteSections = notes,
                s34Sections = s34,
            ),
        )
        assertEquals(
            conclusion,
            resolveOratoryNoteSection(
                sectionId = "sec-2",
                mode = OratoryGeneration.Mode.CONCLUSION,
                noteSections = notes,
                s34Sections = s34,
            ),
        )
    }

    @Test
    fun pontoInexistenteNaoCaiNoFocoAntigo() {
        assertNull(
            resolveOratoryNoteSection(
                sectionId = "sec-9",
                mode = OratoryGeneration.Mode.DEVELOPMENT,
                noteSections = notes,
                s34Sections = s34,
            ),
        )
    }

    @Test
    fun idDaNotaTemMatchDireto() {
        assertEquals(
            point2,
            resolveOratoryNoteSection(
                sectionId = point2.id,
                mode = OratoryGeneration.Mode.DEVELOPMENT,
                noteSections = notes,
                s34Sections = s34,
            ),
        )
    }

    @Test
    fun detectorExigeSubstantivoENumero() {
        assertEquals(2, explicitSectionNumber("Desenvolva o ponto 2"))
        assertEquals(2, explicitSectionNumber("Sugira um texto para o ponto 2"))
        assertEquals(2, explicitSectionNumber("tópico 2"))
        assertEquals(3, explicitSectionNumber("seção 3"))
        assertEquals(9, explicitSectionNumber("ponto 9"))
        assertNull(explicitSectionNumber("Tenho 2 minutos"))
        assertNull(explicitSectionNumber("2"))
        assertNull(explicitSectionNumber("o segundo ponto"))
    }
}
