package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase B — resumo puro da folha de colar (rótulos, cards, ignoradas, rejeição). */
class PastePreviewTest {

    private val talk = planPaste(
        """
        TESOUROS DA PALAVRA DE DEUS
        1. A ordem nos céus (10 min)
        Corpo do discurso.
        """.trimIndent()
    )

    private val talkLong = planPaste(
        """
        TESOUROS DA PALAVRA DE DEUS
        1. A ordem nos céus (10 min)
        Primeiro ponto (Gên. 1:1).

        Segundo ponto (Sal. 19:1).

        Terceiro ponto (Rom. 8:18).
        """.trimIndent()
    )

    private val demo = planPaste(
        "FAÇA SEU MELHOR NO MINISTÉRIO\n" +
            "5. Cultivando o interesse (4 min) DE CASA EM CASA. " +
            "Fale sobre uma das verdades do apêndice A (lmd lição 7 ponto 4)."
    )

    private val consideration = planPaste(
        "6. O que você diria? (6 min) Consideração. Pergunte à assistência."
    )

    private val freeText = planPaste("Uma nota qualquer\ncom duas linhas.")

    private val s34 = planPaste(
        """
        1. Introdução (3 min)
        Fale sobre a importância do tema.
        2. Tópico um (8 min)
        Desenvolva o primeiro ponto.
        3. Tópico dois (8 min)
        Desenvolva o segundo ponto.
        4. Conclusão (3 min)
        Encerre com uma aplicação prática.
        """.trimIndent()
    )

    private val rejected = planPaste(
        """
        TESOUROS DA PALAVRA DE DEUS
        2. Joias espirituais (8 min)
        Tesouros escondidos.
        """.trimIndent()
    )

    @Test
    fun preview_talk_temTresCardsETipoDiscurso() {
        val p = pastePreviewOf(talk)
        assertEquals("V&M · Discurso", p.kindLabel)
        assertTrue(p.detailLines.any { it.contains("Parte 1") })
        assertTrue(p.detailLines.any { it.contains("10 min") })
        assertEquals("3 cards", p.cardsLabel)
        assertTrue(p.canApply)
        assertNull(p.rejectionMessage)
        assertNull(p.ignoredLabel)
    }

    @Test
    fun preview_talk_comTresTopicos_mostraCincoCards() {
        val p = pastePreviewOf(talkLong)
        assertEquals("5 cards", p.cardsLabel)
        assertTrue(p.detailLines.any { it.contains("3 tópicos de desenvolvimento") })
        assertEquals("5 cards criados", pasteApplyFeedback(talkLong))
        assertEquals(
            "3 cards adicionados",
            pasteApplyFeedback(talkLong, currentNoteHasSections = true),
        )
    }

    @Test
    fun preview_demo_temUmCardECenario() {
        val p = pastePreviewOf(demo)
        assertEquals("V&M · Demonstração", p.kindLabel)
        assertEquals("1 card (diálogo)", p.cardsLabel)
        assertTrue(p.detailLines.any { it.contains("Cenário: De casa em casa") })
        assertTrue(p.detailLines.any { it.contains("Referência: lmd lição 7 ponto 4") })
        assertTrue(p.canApply)
    }

    @Test
    fun preview_consideracao_eRejeitada() {
        val p = pastePreviewOf(consideration)
        assertEquals("V&M · sem parte aplicável", p.kindLabel)
        assertEquals("0 cards", p.cardsLabel)
        assertFalse(p.canApply)
        assertTrue(p.rejectionMessage.orEmpty().contains("não é suportada"))
    }

    @Test
    fun preview_textoLivre_temUmCardCorpo() {
        val p = pastePreviewOf(freeText)
        assertEquals("Texto livre", p.kindLabel)
        assertEquals("1 card (Corpo)", p.cardsLabel)
        assertTrue(p.canApply)
    }

    @Test
    fun preview_s34_mostraIntroducaoCorposConclusao() {
        val p = pastePreviewOf(s34)
        assertEquals("Esboço S-34", p.kindLabel)
        assertEquals("Introdução + 4 corpos + Conclusão", p.cardsLabel)
        assertTrue(p.detailLines.any { it.contains("4 seções") })
        assertTrue(p.canApply)
    }

    @Test
    fun preview_rejeitado_bloqueiaApplyEMostraMensagem() {
        val p = pastePreviewOf(rejected)
        assertEquals("V&M · sem parte aplicável", p.kindLabel)
        assertEquals("0 cards", p.cardsLabel)
        assertFalse(p.canApply)
        assertNotNull(p.rejectionMessage)
        assertTrue(p.rejectionMessage!!.contains("nenhuma das partes"))
    }

    @Test
    fun preview_mostraPartesIgnoradas() {
        val plan = planPaste(
            """
            4. Iniciando conversas (3 min) DE CASA EM CASA
            Fale sobre uma notícia local.

            5. Cultivando o interesse (4 min) TESTEMUNHO INFORMAL
            Retome a conversa anterior.
            """.trimIndent()
        )
        val p = pastePreviewOf(plan)
        assertEquals(1, p.ignoredParts.size)
        assertEquals("1 parte ignorada", p.ignoredLabel)
        assertEquals(5, p.ignoredParts.single().partNumber)
    }

    @Test
    fun feedback_talkNovoECorrente() {
        assertEquals("3 cards criados", pasteApplyFeedback(talk))
        assertEquals(
            "1 card adicionado",
            pasteApplyFeedback(talk, currentNoteHasSections = true),
        )
    }

    @Test
    fun feedback_demoConsideracaoFreeText() {
        assertEquals("1 card criado", pasteApplyFeedback(demo))
        assertTrue(
            pasteApplyFeedback(consideration).contains("não é suportada"),
        )
        assertEquals("1 card criado", pasteApplyFeedback(freeText))
    }

    @Test
    fun feedback_s34HomeERevisaoNoChat() {
        assertEquals("6 seções criadas", pasteApplyFeedback(s34))
        assertEquals(
            "Material enviado para revisão no chat.",
            pasteApplyFeedback(s34, reviewInChat = true),
        )
    }

    @Test
    fun feedback_ignoradasSaoMencionadas() {
        val plan = planPaste(
            """
            4. Iniciando conversas (3 min) DE CASA EM CASA
            Fale sobre uma notícia local.

            5. Cultivando o interesse (4 min) TESTEMUNHO INFORMAL
            Retome a conversa anterior.
            """.trimIndent()
        )
        assertTrue(pasteApplyFeedback(plan).contains("1 parte ignorada"))
    }
}
