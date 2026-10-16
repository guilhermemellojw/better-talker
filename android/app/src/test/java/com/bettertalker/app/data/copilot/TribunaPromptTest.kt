package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertTrue
import org.junit.Test

/** #20-fix-a — prompt da Tribuna: mapa de memória, não resumo. Puro. */
class TribunaPromptTest {

    private fun section(id: String, title: String, html: String) = SpeechSection(
        id = id, noteId = "n1", order = 1, role = SectionRole.BODY, title = title,
        minutes = 5, contentHtml = html, bibleRefs = emptyList(),
        publicationRefs = emptyList(), methodPrinciple = null,
        createdAt = 1, updatedAt = 2,
    )

    @Test
    fun promptTemAsSecoesEAsRegras() {
        val p = buildTribunaPrompt(
            listOf(
                section("sec-1", "PONTO 1", "<p>Texto do ponto um.</p>"),
                section("sec-2", "PONTO 2", "<p>Texto do ponto dois.</p>"),
            )
        )
        assertTrue(p.contains("[sec-1]"))
        assertTrue(p.contains("PONTO 1"))
        assertTrue(p.contains("Texto do ponto um."))
        assertTrue(p.contains("[sec-2]"))
        assertTrue(p.contains("NÃO invente refs"))
        assertTrue(p.contains("Retorne APENAS JSON válido"))
    }

    @Test
    fun promptEhMapaDeMemoriaNaoResumo() {
        val p = buildTribunaPrompt(listOf(section("s", "T", "<p>x</p>")))
        assertTrue(p.contains("MAPA DE MEMÓRIA"))
        assertTrue(p.contains("não um resumo"))
    }
}
