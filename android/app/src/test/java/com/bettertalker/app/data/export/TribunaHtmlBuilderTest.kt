package com.bettertalker.app.data.export

import com.bettertalker.app.domain.speech.TribunaCapsule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #20-fix-c — HTML da Tribuna: os 4 campos, omissão de vazios e escape. Puro. */
class TribunaHtmlBuilderTest {

    private fun card(order: Int, title: String, cap: TribunaCapsule?) = TribunaCardData(
        order = order, sectionTitle = title, minutes = 6, capsule = cap,
    )

    private fun cap(id: String, idea: String, ref: String?, anchor: String, tr: String?) =
        TribunaCapsule(
            sectionId = id, mainIdea = idea, bibleRef = ref,
            anchorPhrase = anchor, transition = tr,
        )

    @Test
    fun tresCardsComCapsulaTemOsQuatroCampos() {
        val html = buildTribunaHtml(
            "Meu discurso",
            listOf(
                card(0, "PONTO 1", cap("s1", "Ideia um", "Jó 38", "Âncora um", "Transição um")),
                card(1, "PONTO 2", cap("s2", "Ideia dois", null, "Âncora dois", null)),
                card(2, "PONTO 3", cap("s3", "Ideia três", "Sal 103", "Âncora três", "Transição três")),
            ),
        )
        assertTrue(html.contains("Ideia um"))
        assertTrue(html.contains("Jó 38"))
        assertTrue(html.contains("Âncora um"))
        assertTrue(html.contains("Transição um"))
        assertTrue(html.contains("1. PONTO 1"))
        assertTrue(html.contains("Meu discurso"))
    }

    @Test
    fun cardSemCapsulaEhOmitido() {
        val html = buildTribunaHtml(
            "T",
            listOf(
                card(0, "VAZIO", null),
                card(1, "OK", cap("s2", "Ideia", null, "Âncora", null)),
            ),
        )
        assertFalse(html.contains("VAZIO"))
        assertTrue(html.contains("OK"))
    }

    @Test
    fun todosVaziosTemMensagem() {
        val html = buildTribunaHtml("T", listOf(card(0, "A", null)))
        assertTrue(html.contains("Nenhuma Tribuna gerada"))
    }

    @Test
    fun escapeDeCaracteresEspeciais() {
        val html = buildTribunaHtml(
            "Nota <script>",
            listOf(card(0, "A & B", cap("s1", "<b>Ideia</b>", null, "Âncora & cia", null))),
        )
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("A &amp; B"))
        assertTrue(html.contains("&lt;b&gt;Ideia&lt;/b&gt;"))
    }
}
