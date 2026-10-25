package com.bettertalker.app.ui.stage

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1b-4 — texto do Ensaio por tipo (V&M × S-34). */
class StageContentTest {

    private fun section(
        id: String,
        order: Int,
        role: SectionRole = SectionRole.BODY,
        title: String = "Seção",
        minutes: Int = 4,
        html: String = "",
    ) = SpeechSection(
        id = id, noteId = "n1", order = order, role = role, title = title,
        minutes = minutes, contentHtml = html, bibleRefs = emptyList(),
        publicationRefs = emptyList(), methodPrinciple = null,
        createdAt = 0, updatedAt = 0,
    )

    private fun sub(
        id: String,
        sectionId: String,
        order: Int,
        outline: String,
        developed: String = "",
    ) = SubPoint(
        id = id, sectionId = sectionId, order = order, outlineText = outline,
        bibleRefs = emptyList(), publicationRefs = emptyList(), instruction = null,
        developedHtml = developed, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun s34_leSubpontosDesenvolvidosOuEsbocoSemTitulos() {
        val sections = listOf(
            section("s1", 0, title = "Ponto 1", html = "<p>conteúdo antigo</p>"),
            section("s2", 1, title = "Ponto 2"),
        )
        val subs = mapOf(
            "s1" to listOf(sub("sp1", "s1", 0, "Ponto A", "<p>Fala desenvolvida A</p>")),
            "s2" to listOf(sub("sp2", "s2", 0, "Ponto esboço B")),
        )

        val content = buildStageContent(DiscourseType.S34_DISCOURSE, sections, subs)

        assertTrue(content.contains("Fala desenvolvida A"))
        assertTrue(content.contains("Ponto esboço B"))
        assertFalse(content.contains("Ponto 1"))
        assertFalse(content.contains("Ponto 2"))
        assertFalse(content.contains("conteúdo antigo"))
    }

    @Test
    fun s34_semSubpontos_usaContentHtml() {
        val content = buildStageContent(
            DiscourseType.S34_DISCOURSE,
            listOf(section("s1", 0, html = "<p>Conteúdo do esboço</p>")),
            emptyMap(),
        )
        assertEquals("Conteúdo do esboço", content)
    }

    @Test
    fun ministryTalk_leContentHtmlSemTituloNemMetadado() {
        val content = buildStageContent(
            DiscourseType.MINISTRY_TALK,
            listOf(
                section("i", 0, SectionRole.INTRO, "Introdução", 1),
                section(
                    "b", 1, title = "Jesus não é o Deus Todo-poderoso · lmd apêndice A ponto 20",
                    minutes = 2, html = "<p>Fala do discurso.</p>",
                ),
                section("c", 2, SectionRole.CONCLUSION, "Conclusão", 1),
            ),
            emptyMap(),
        )
        assertEquals("Fala do discurso.", content)
    }

    @Test
    fun ministryTalk_vazio_naoLeTitulos() {
        val content = buildStageContent(
            DiscourseType.MINISTRY_TALK,
            listOf(
                section("i", 0, SectionRole.INTRO, "Introdução", 1),
                section("b", 1, title = "Jesus não é o Deus Todo-poderoso · lmd apêndice A ponto 20", minutes = 2),
                section("c", 2, SectionRole.CONCLUSION, "Conclusão", 1),
            ),
            emptyMap(),
        )
        assertTrue(content.isBlank())
    }

    @Test
    fun demo_leContentHtmlSemTitulo() {
        val content = buildStageContent(
            DiscourseType.MINISTRY_PART,
            listOf(
                section(
                    "b", 0, title = "DE CASA EM CASA · lmd lição 7 ponto 4",
                    html = "<p>Publicador: Olá!</p><p>Morador: Bom dia.</p>",
                ),
            ),
            emptyMap(),
        )
        assertTrue(content.contains("Publicador: Olá!"))
        assertTrue(content.contains("Morador: Bom dia."))
        assertFalse(content.contains("DE CASA EM CASA"))
    }

    @Test
    fun freeDemo_leContentHtml() {
        val content = buildStageContent(
            DiscourseType.FREE_DEMO,
            listOf(
                section(
                    "b", 0, title = "Demonstração", minutes = 4,
                    html = "<p><b>Publicador:</b> Olá!</p><p><b>Morador:</b> Bom dia.</p>",
                ),
            ),
            emptyMap(),
        )
        assertTrue(content.contains("Publicador: Olá!"))
        assertTrue(content.contains("Morador: Bom dia."))
    }

    @Test
    fun treasuresTalk_leContentHtml() {
        val content = buildStageContent(
            DiscourseType.TREASURES_TALK,
            listOf(
                section("i", 0, SectionRole.INTRO, "Introdução", 1),
                section("b", 1, title = "Ponto 1", minutes = 8, html = "<p>Primeiro ponto.</p>"),
                section("c", 2, SectionRole.CONCLUSION, "Conclusão", 1),
            ),
            emptyMap(),
        )
        assertEquals("Primeiro ponto.", content)
    }
}
