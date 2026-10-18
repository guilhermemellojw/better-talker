package com.bettertalker.app.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modo Teleprompter: parse dos turnos + fala sem rótulos + ciclo do Modo Dupla. */
class StageTurnsTest {

    private val demoHtml =
        "<p><b>Publicador:</b> Ola, bom dia!</p>" +
            "<p><b>Morador:</b> Tudo bem, obregado.</p>" +
            "<p><b>Publicador:</b> Gostaria de falar da Biblia.</p>"

    @Test
    fun parseStageTurns_separaPapeisEFalas() {
        val turns = parseStageTurns(demoHtml)
        assertEquals(3, turns.size)
        assertEquals("Publicador", turns[0].speaker)
        assertEquals("Ola, bom dia!", turns[0].text)
        assertEquals("Morador", turns[1].speaker)
        assertEquals("Tudo bem, obregado.", turns[1].text)
        assertEquals("Gostaria de falar da Biblia.", turns[2].text)
    }

    @Test
    fun parseStageTurns_decodificaEntidades() {
        val html = "<p><b>Publicador:</b> Vamos come&ccedil;ar com uma pergunta&period;</p>"
        val turns = parseStageTurns(html)
        assertEquals("Vamos começar com uma pergunta.", turns[0].text)
    }

    @Test
    fun parseStageTurns_rotuloSemNegrito_viraTurno() {
        // "Usar no card" pode gravar o papel sem <b> (e ":" como "&colon;").
        val html = "<p>Publicador&colon; Ol&aacute;, boa tarde&excl;</p>" +
            "<p>Morador&colon; Bom dia&period;</p>"
        val turns = parseStageTurns(html)
        assertEquals(2, turns.size)
        assertEquals("Publicador", turns[0].speaker)
        assertEquals("Olá, boa tarde!", turns[0].text)
        assertEquals("Morador", turns[1].speaker)
        assertEquals("Bom dia.", turns[1].text)
    }

    @Test
    fun parseStageTurns_decodificaPontuacaoDoEditor() {
        val html = "<p><b>Morador:</b> sem guerra&comma; sem doen&ccedil;a&mldr; " +
            "&lpar;abre a publica&ccedil;&atilde;o&rpar;</p>"
        val turns = parseStageTurns(html)
        assertEquals("sem guerra, sem doença… (abre a publicação)", turns[0].text)
    }

    @Test
    fun parseStageTurns_fraseLongaComDoisPontos_naoViraTurno() {
        // Parágrafo sem rótulo (frase longa com ":") não vira turno.
        assertTrue(
            parseStageTurns(
                "<p>Então deixa eu te perguntar uma coisa: se você pudesse escolher?</p>"
            ).isEmpty()
        )
    }

    @Test
    fun parseStageTurns_toleraSpansERestoDeTags() {
        val html = "<p><b>Morador:</b> <span style=\"color: red;\">certo</span>, obrigado.</p>"
        val turns = parseStageTurns(html)
        assertEquals(1, turns.size)
        assertEquals("certo, obrigado.", turns[0].text)
    }

    @Test
    fun parseStageTurns_semDialogo_retornaVazio() {
        assertTrue(parseStageTurns("<p>Texto corrido sem papeis.</p>").isEmpty())
        assertTrue(parseStageTurns("").isEmpty())
    }

    @Test
    fun parseStageTurns_negritoNoMeio_naoViraTurno() {
        // Negrito sem rótulo no início do parágrafo não é turno.
        assertTrue(parseStageTurns("<p>texto <b>destacado</b> fim</p>").isEmpty())
    }

    @Test
    fun stageSpeechText_semRotulos() {
        val turns = parseStageTurns(demoHtml)
        val speech = stageSpeechText(turns, "fallback")
        assertEquals("Ola, bom dia! Tudo bem, obregado. Gostaria de falar da Biblia.", speech)
        assertTrue(speech, !speech.contains("Publicador"))
        assertTrue(speech, !speech.contains("Morador"))
    }

    @Test
    fun stageSpeechText_semTurnos_usaFallback() {
        assertEquals("texto corrido", stageSpeechText(emptyList(), "texto corrido"))
    }

    @Test
    fun nextDuoRole_cicla() {
        val speakers = listOf("Publicador", "Morador")
        assertNull(nextDuoRole(null, emptyList()))
        assertEquals("Publicador", nextDuoRole(null, speakers))
        assertEquals("Morador", nextDuoRole("Publicador", speakers))
        assertNull(nextDuoRole("Morador", speakers))
    }
}
