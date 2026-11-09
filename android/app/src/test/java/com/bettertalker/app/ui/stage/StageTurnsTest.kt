package com.bettertalker.app.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------- v5.3: roteiro completo com narração ----------

    @Test
    fun parseStageScript_preservaNarracaoETurnos() {
        val html = "<p>Ana entra na sala e cumprimenta o morador.</p>" +
            "<p><b>Ana:</b> Olá, tudo bem?</p>" +
            "<p>O morador abre a porta.</p>" +
            "<p><b>Morador:</b> Bom dia.</p>"
        val script = parseStageScript(html)
        assertEquals(4, script.size)
        assertEquals(StageTurnKind.NARRATION, script[0].kind)
        assertEquals("Ana entra na sala e cumprimenta o morador.", script[0].text)
        assertEquals(StageTurnKind.SPEECH, script[1].kind)
        assertEquals("Ana", script[1].speaker)
        assertEquals(StageTurnKind.NARRATION, script[2].kind)
        assertEquals(StageTurnKind.SPEECH, script[3].kind)
        assertEquals("Morador", script[3].speaker)
    }

    @Test
    fun parseStageScript_fallbackDeTextoCorridoViraNarracao() {
        val script = parseStageScript("Texto corrido sem papeis.")
        assertEquals(1, script.size)
        assertEquals(StageTurnKind.NARRATION, script[0].kind)
        assertEquals("Texto corrido sem papeis.", script[0].text)
    }

    @Test
    fun parseStageScript_preservaListasETitulos() {
        val html = "<h2>Preparação</h2><ul><li>Abra a publicação</li>" +
            "<li>Leia o parágrafo</li></ul>"
        val script = parseStageScript(html)
        assertEquals(
            listOf("Preparação", "Abra a publicação", "Leia o parágrafo"),
            script.map { it.text },
        )
        assertTrue(script.all { it.kind == StageTurnKind.NARRATION })
    }

    @Test
    fun parseStageScript_preservaTextoForaDosParagrafos() {
        val script = parseStageScript("Abertura <p><b>Ana:</b> fala</p> Encerramento")
        assertEquals(listOf("Abertura", "fala", "Encerramento"), script.map { it.text })
        assertEquals(StageTurnKind.NARRATION, script[0].kind)
        assertEquals(StageTurnKind.SPEECH, script[1].kind)
        assertEquals(StageTurnKind.NARRATION, script[2].kind)
    }

    @Test
    fun parseStageTurns_continuaSoComFalas() {
        val html = "<p>Ana entra.</p><p><b>Ana:</b> Olá.</p>"
        val turns = parseStageTurns(html)
        assertEquals(1, turns.size)
        assertEquals("Ana", turns[0].speaker)
        assertFalse(turns[0].isNarration)
    }

    @Test
    fun stageSpeechText_ignoraNarracao() {
        val script = parseStageScript(
            "<p>Ana entra.</p><p><b>Ana:</b> Olá.</p>"
        )
        assertEquals("Olá.", stageSpeechText(script, "fallback"))
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
