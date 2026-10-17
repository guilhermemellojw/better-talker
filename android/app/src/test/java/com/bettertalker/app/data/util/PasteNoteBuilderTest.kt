package com.bettertalker.app.data.util

import com.bettertalker.app.domain.speech.DiscourseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 1 "Colar e Pronto": texto colado → nota pronta (TALK/DEMO/Genérico). */
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

    // ---------- DEMO: turnos no HTML ----------

    @Test
    fun demo_criaNotaTipadaComTituloEMinutosDaParte() {
        val built = buildPastedNote(demoText)
        assertEquals(DiscourseType.MINISTRY_PART, built.discourseType)
        assertEquals("Comece uma conversa", built.title)
        assertEquals(3, built.bodyMinutes)
    }

    @Test
    fun demo_quebraEmTurnosComPapelEmNegrito() {
        val built = buildPastedNote(demoText)
        val html = built.bodyHtml ?: error("demo deve ter corpo")
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
        val html = built.bodyHtml ?: error("demo deve ter corpo")
        assertFalse(html, html.contains("<b>"))
        assertTrue(html, html.contains("<p>Publicador: só eu falando aqui.</p>"))
    }

    @Test
    fun demo_escapaHtmlDoTextoColado() {
        val built = buildPastedNote(
            "FAÇA SEU MELHOR NO MINISTÉRIO\n4. Visita (3 min)\n" +
                "Publicador: leia <b>isto</b> & aquilo\nMorador: certo"
        )
        val html = built.bodyHtml ?: error("demo deve ter corpo")
        assertTrue(html, html.contains("&lt;b&gt;isto&lt;/b&gt; &amp; aquilo"))
    }

    // ---------- TALK ----------

    @Test
    fun talk_criaNotaComTituloDaParteECorpo() {
        val built = buildPastedNote(talkText)
        assertEquals(DiscourseType.TREASURES_TALK, built.discourseType)
        assertEquals("A ordem nos céus", built.title)
        assertEquals(10, built.bodyMinutes)
        val html = built.bodyHtml ?: error("talk deve ter corpo")
        assertTrue(html, html.contains("<p>Vamos começar com uma pergunta simples.</p>"))
        assertTrue(html, html.contains("<p>Quando você olha para o céu, o que sente?</p>"))
    }

    // ---------- Genérico ----------

    @Test
    fun generico_notaSimplesComTextoSemSecao() {
        val built = buildPastedNote("Uma nota qualquer\ncom duas linhas.")
        assertEquals(DiscourseType.S34_DISCOURSE, built.discourseType)
        assertEquals("Uma nota qualquer", built.title)
        assertNull(built.bodyHtml)
        assertEquals("Uma nota qualquer\ncom duas linhas.", built.plainText)
    }

    @Test
    fun vazio_naoQuebra() {
        val built = buildPastedNote("   ")
        assertEquals("Nota colada", built.title)
        assertNull(built.bodyHtml)
    }
}
