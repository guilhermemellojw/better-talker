package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.OratoryFidelityCheck
import com.bettertalker.app.data.llm.GroundednessVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 — avisos não bloqueantes dos verificadores no chat remoto. */
class GateWarningsTest {

    private val corpus = listOf(
        "Gên 1:26 Deus criou os humanos para viver uma vida perfeita, eterna, na Terra."
    )

    @Test
    fun versiculoSemFonteGeraAviso() {
        val notice = citationRevisionNotice("Como diz Gên 99:99, devemos confiar.", corpus)
        assertNotNull(notice)
        assertTrue(notice!!.startsWith("⚠️ Revise:"))
        assertTrue(notice.contains("gên 99:99"))
    }

    @Test
    fun citacaoNumeradaForaDoCorpusGeraAviso() {
        val notice = citationRevisionNotice("Veja [9] sobre isso.", corpus)
        assertNotNull(notice)
        assertTrue(notice!!.contains("[9]"))
    }

    @Test
    fun textoLimpoNaoGeraAviso() {
        assertNull(citationRevisionNotice("Deus criou os humanos para viver para sempre.", corpus))
        assertNull(citationRevisionNotice("Como diz Gên 1:26, devemos confiar.", corpus))
    }

    @Test
    fun avisoUnicoDoChatResumeCategorias() {
        val text = "O texto fala de esperança. " +
            "\"Todos os justos herdarão a terra para sempre.\""
        val verified = GroundednessVerifier.verify(text, corpus, warnOnly = true)
        val notice = chatVerificationNotice(text, corpus, verified)
        assertNotNull(notice)
        assertTrue(notice!!.startsWith("⚠️ Revise:"))
        assertTrue(notice.contains("1 trecho sinalizado"))
        assertTrue(notice.contains("1 citação"))
        assertTrue(notice.contains("O texto foi mantido"))
        assertEquals(1, Regex("⚠️").findAll(notice).count())
    }

    @Test
    fun avisoUnicoSomeQuandoNaoHaFlagNemCitacao() {
        val text = "Deus criou os humanos para viver para sempre."
        val verified = GroundednessVerifier.verify(text, corpus, warnOnly = true)
        assertNull(chatVerificationNotice(text, corpus, verified))
    }

    @Test
    fun politicaDoChatMantemTextoEAnexaUmAviso() {
        val text = "O texto fala de esperança. " +
            "\"Todos os justos herdarão a terra para sempre.\""
        val out = verifyChatText(text, corpus)
        assertEquals(text, out.text)
        assertNotNull(out.notice)
        assertTrue(out.result.metrics.hasFlags)
        assertEquals(1, Regex("⚠️").findAll(out.notice!!).count())
    }

    @Test
    fun politicaDoChatLimpoNaoAnexaAviso() {
        val text = "Deus criou os humanos para viver para sempre."
        val out = verifyChatText(text, corpus)
        assertEquals(text, out.text)
        assertNull(out.notice)
        assertFalse(out.result.metrics.hasFlags)
    }

    @Test
    fun falaDeRolePlayNaoGeraAvisoDeCitacao() {
        val text = "<p><b>Morador:</b> “você condena quem comemora a festa de aniversário?”</p>" +
            "<p><b>Publicador:</b> “não condeno; só quero explicar minha razão.”</p>"
        val out = verifyChatText(text, corpus)
        assertEquals(text, out.text)
        assertNull(out.notice)
    }

    @Test
    fun citacaoRealSemFonteContinuaAvisando() {
        val text = "Como diz João 3:16, " +
            "“Deus amou tanto que enviou seu Filho para morrer por todos os pecados do mundo.”"
        val out = verifyChatText(text, corpus)
        assertNotNull(out.notice)
        assertTrue(out.notice!!.contains("citação"))
    }

    @Test
    fun fidelidadeInventadaGeraAvisoComMotivo() {
        val notice = fidelityRevisionNotice(
            OratoryFidelityCheck.Report(
                inventedReferences = listOf("Hebreus 10:23"),
                leakedReferences = emptyList(),
                unsupportedNumbers = listOf("7"),
            )
        )
        assertNotNull(notice)
        assertTrue(notice!!.startsWith("⚠️ Revise:"))
        assertTrue(notice.contains("Hebreus 10:23"))
        assertTrue(notice.contains("7"))
    }

    @Test
    fun fidelidadeOkNaoGeraAviso() {
        assertNull(
            fidelityRevisionNotice(OratoryFidelityCheck.Report(emptyList(), emptyList(), emptyList()))
        )
    }

    @Test
    fun bannerApareceAntesDoPrimeiroUso() {
        assertTrue(shouldShowSetupBanner(isSetupComplete = false, dismissed = false, seen = false))
    }

    @Test
    fun bannerSomeAposPrimeiroUsoOuSetupOuDispensa() {
        // Após o 1º envio, nunca mais — mesmo sem vínculos.
        assertFalse(shouldShowSetupBanner(isSetupComplete = false, dismissed = false, seen = true))
        // Setup completo: sem banner, com ou sem uso anterior.
        assertFalse(shouldShowSetupBanner(isSetupComplete = true, dismissed = false, seen = false))
        // Dispensa volátil da sessão continua valendo.
        assertFalse(shouldShowSetupBanner(isSetupComplete = false, dismissed = true, seen = false))
    }
}
