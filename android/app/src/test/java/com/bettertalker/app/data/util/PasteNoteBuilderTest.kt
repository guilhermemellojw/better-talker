package com.bettertalker.app.data.util

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.OutlineConverter
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1 "Colar e Pronto": texto colado → nota pronta.
 * TALK (template de discurso) / DEMO / Esboço estruturado / Genérico.
 */
class PasteNoteBuilderTest {

    private val demoText = """
        FAÇA SEU MELHOR NO MINISTÉRIO
        4. Comece uma conversa (3 min)
        Publicador: Olá, bom dia! Como vai?
        Morador: Tudo bem, obrigado.
        Publicador: Eu gostaria de conversar sobre a Bíblia.
        Morador: Pode falar.
    """.trimIndent()

    private val talkText = """
        TESOUROS DA PALAVRA DE DEUS
        1. A ordem nos céus (10 min)
        Vamos começar com uma pergunta simples.
        Quando você olha para o céu, o que sente?
    """.trimIndent()

    /** Seção única (demo/genérico). */
    private fun only(built: PastedNote): PastedSection {
        assertEquals(1, built.sections.size)
        return built.sections.first()
    }

    // ---------- DEMO: turnos no HTML ----------

    @Test
    fun demo_criaNotaTipadaComTituloEMinutosDaParte() {
        val built = buildPastedNote(demoText)
        assertEquals(DiscourseType.MINISTRY_PART, built.discourseType)
        // Nota: parte + duração; card da seção: descrição (aqui, sem pré-turno).
        assertEquals("Comece uma conversa (3 min)", built.title)
        val s = only(built)
        assertEquals(SectionRole.BODY, s.role)
        assertEquals("Comece uma conversa", s.title)
        assertEquals(3, s.minutes)
    }

    @Test
    fun demo_quebraEmTurnosComPapelEmNegrito() {
        val html = only(buildPastedNote(demoText)).contentHtml
        assertTrue(html, html.contains("<p><b>Publicador:</b> Olá, bom dia! Como vai?</p>"))
        assertTrue(html, html.contains("<p><b>Morador:</b> Tudo bem, obrigado.</p>"))
        assertTrue(html, html.contains("<p><b>Publicador:</b> Eu gostaria de conversar sobre a Bíblia.</p>"))
        assertTrue(html, html.contains("<p><b>Morador:</b> Pode falar.</p>"))
        // Ordem preservada: 1º turno do Publicador antes do 1º do Morador.
        assertTrue(html.indexOf("Publicador") < html.indexOf("Morador"))
    }

    @Test
    fun demo_splitterFlexivel_espacosEEtiquetasVariadas() {
        val turns = splitDemonstrationTurns(
            "  PUBLICADOR :  fala um  \nMoradora: fala dois\nlinha extra do dois"
        )
        assertEquals(2, turns.size)
        assertEquals("PUBLICADOR", turns[0].speaker)
        assertEquals("fala um", turns[0].text)
        assertEquals("Moradora", turns[1].speaker)
        assertEquals("fala dois linha extra do dois", turns[1].text)
    }

    @Test
    fun demo_umSoPapel_naoViraDialogo() {
        val built = buildPastedNote(
            "FAÇA SEU MELHOR NO MINISTÉRIO\n4. Visita (3 min)\nPublicador: só eu falando aqui."
        )
        // Sem diálogo válido, o texto inteiro é a descrição (título do card)
        // e o corpo fica vazio para a redação.
        val s = only(built)
        assertEquals("Publicador: só eu falando aqui.", s.title)
        assertEquals("", s.contentHtml)
    }

    @Test
    fun demo_escapaHtmlDoTextoColado() {
        val html = only(
            buildPastedNote(
                "FAÇA SEU MELHOR NO MINISTÉRIO\n4. Visita (3 min)\n" +
                    "Publicador: leia <b>isto</b> & aquilo\nMorador: certo"
            )
        ).contentHtml
        assertTrue(html, html.contains("&lt;b&gt;isto&lt;/b&gt; &amp; aquilo"))
    }

    // ---------- TALK: template de discurso (Intro/Desenvolvimento/Conclusão) ----------

    @Test
    fun talk_aplicaTemplateDeDiscurso() {
        val built = buildPastedNote(talkText)
        assertEquals(DiscourseType.TREASURES_TALK, built.discourseType)
        // Nota: parte + duração.
        assertEquals("A ordem nos céus (10 min)", built.title)
        assertTrue(built.speechTemplate)
        // Exatamente 3 seções: INTRO + BODY (com o texto) + CONCLUSION.
        assertEquals(3, built.sections.size)
        val intro = built.sections[0]
        val body = built.sections[1]
        val conclusion = built.sections[2]
        assertEquals(SectionRole.INTRO, intro.role)
        assertEquals("Introdução", intro.title)
        assertEquals(EDGE_SECTION_MINUTES, intro.minutes)
        assertEquals("", intro.contentHtml)
        assertEquals(SectionRole.BODY, body.role)
        assertEquals("A ordem nos céus", body.title)
        assertEquals(10, body.minutes)
        assertTrue(body.contentHtml, body.contentHtml.contains("<p>Vamos começar com uma pergunta simples.</p>"))
        assertTrue(body.contentHtml, body.contentHtml.contains("<p>Quando você olha para o céu, o que sente?</p>"))
        assertEquals(SectionRole.CONCLUSION, conclusion.role)
        assertEquals("Conclusão", conclusion.title)
        assertEquals(EDGE_SECTION_MINUTES, conclusion.minutes)
        assertEquals("", conclusion.contentHtml)
    }

    // ---------- Esboço estruturado (S-34): OutlineConverter cuida das seções ----------

    @Test
    fun esboco_estruturado_viraOutlineParaConversao() {
        val text = """
            1. Introdução (3 min)
            Fale sobre a importância do tema.
            2. Tópico um (8 min)
            Desenvolva o primeiro ponto.
            3. Tópico dois (8 min)
            Desenvolva o segundo ponto.
            4. Conclusão (3 min)
            Encerre com uma aplicação prática.
        """.trimIndent()
        val built = buildPastedNote(text)
        assertEquals(DiscourseType.S34_DISCOURSE, built.discourseType)
        assertTrue(built.sections.isEmpty())
        val outline = built.outline ?: error("esboço deve virar outline")
        assertEquals(4, outline.sections.size)
        assertTrue(outline.sections[1].title, outline.sections[1].title.contains("Tópico um"))
        assertEquals(8, outline.sections[1].minutes)
        // Conversão real (pura): Introdução sintética + 4 corpos + Conclusão.
        val conv = OutlineConverter().convert(outline, "n1", DiscourseType.S34_DISCOURSE)
        assertNotNull(conv.intro)
        assertEquals(4, conv.bodies.size)
        assertNotNull(conv.conclusion)
    }

    // ---------- Genérico: o texto vira seção BODY (antes ficava invisível) ----------

    @Test
    fun generico_viraSecaoCorpoComOTexto() {
        val built = buildPastedNote("Uma nota qualquer\ncom duas linhas.")
        assertEquals(DiscourseType.S34_DISCOURSE, built.discourseType)
        assertEquals("Uma nota qualquer", built.title)
        assertNull(built.outline)
        val s = only(built)
        assertEquals(SectionRole.BODY, s.role)
        assertEquals("Corpo", s.title)
        assertEquals(GENERIC_SECTION_MINUTES, s.minutes)
        assertTrue(s.contentHtml, s.contentHtml.contains("<p>Uma nota qualquer</p>"))
        assertTrue(s.contentHtml, s.contentHtml.contains("<p>com duas linhas.</p>"))
        assertEquals("Uma nota qualquer\ncom duas linhas.", built.plainText)
    }

    @Test
    fun textoCorrido_semMinutos_naoViraEsboco() {
        val built = buildPastedNote(
            "Hoje eu quero falar sobre a esperança. " +
                "A Bíblia promete um novo mundo. Vamos ver o que isso significa na prática."
        )
        assertNull(built.outline)
        assertEquals(1, built.sections.size)
    }

    @Test
    fun vazio_naoQuebra() {
        val built = buildPastedNote("   ")
        assertEquals("Nota colada", built.title)
        assertTrue(built.sections.isEmpty())
    }

    // ---------- UX "Colar e Pronto": parte solta vira MINISTRY_PART ----------

    @Test
    fun parteSolta_viraNotaDemonstracaoComDescricaoNoCard() {
        val built = buildPastedNote(
            "5. Cultivando o interesse (4 min) DE CASA EM CASA. " +
                "Fale sobre como iniciar uma conversa."
        )
        assertEquals(DiscourseType.MINISTRY_PART, built.discourseType)
        // Nota: parte + duração; card da seção: a descrição; corpo vazio.
        assertEquals("Cultivando o interesse (4 min)", built.title)
        val s = only(built)
        assertEquals("DE CASA EM CASA. Fale sobre como iniciar uma conversa.", s.title)
        assertEquals("", s.contentHtml)
        assertEquals(4, s.minutes)
    }
}
