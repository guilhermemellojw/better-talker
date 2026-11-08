package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.2 — prompt por seção da Tribuna 2.0 (folha de cues). Puro. */
class TribunaPromptTest {

    private fun section(
        id: String,
        title: String,
        html: String,
        minutes: Int = 5,
        bibleRefs: List<String> = emptyList(),
    ) = SpeechSection(
        id = id, noteId = "n1", order = 1, role = SectionRole.BODY, title = title,
        minutes = minutes, contentHtml = html, bibleRefs = bibleRefs,
        publicationRefs = emptyList(), methodPrinciple = null,
        createdAt = 1, updatedAt = 2,
    )

    @Test
    fun promptTemSecaoCuesERegras() {
        val p = buildTribunaSectionPrompt(
            section = section("sec-1", "PONTO 1", "<p>Texto do ponto um.</p>"),
            nextSectionTitle = "PONTO 2",
        )
        assertTrue(p.contains("MAPA DE MEMÓRIA"))
        assertTrue(p.contains("não um resumo"))
        assertTrue(p.contains("id: sec-1"))
        assertTrue(p.contains("PONTO 1"))
        assertTrue(p.contains("Texto do ponto um."))
        assertTrue(p.contains("\"cues\""))
        assertTrue(p.contains("\"timeMinutes\""))
        assertTrue(p.contains("\"keyTerms\""))
        assertTrue(p.contains("NÃO invente refs"))
        assertTrue(p.contains("Retorne APENAS JSON válido"))
        assertTrue(p.contains("PRÓXIMA SEÇÃO"))
        assertTrue(p.contains("PONTO 2"))
    }

    @Test
    fun promptIncluiSoALegendaDaImagemNuncaBytes() {
        val p = buildTribunaSectionPrompt(
            section = section("s", "T", "<p>x</p>"),
            images = listOf(
                TribunaImageHint(id = "img-1", caption = "Gráfico da criação", kind = "chart"),
            ),
        )
        assertTrue(p.contains("[img-1]"))
        assertTrue(p.contains("Gráfico da criação"))
        assertTrue(p.contains("os bytes não são enviados"))
        assertFalse(p.contains("image/jpeg"))
        assertFalse(p.contains("data:image"))
    }

    @Test
    fun promptSemImagemNaoAbreBlocoDeImagens() {
        val p = buildTribunaSectionPrompt(section("s", "T", "<p>x</p>"))
        assertFalse(p.contains("--- IMAGENS ANEXADAS"))
    }

    @Test
    fun promptIncluiRefsEAbordagemQuandoExistem() {
        val p = buildTribunaSectionPrompt(
            section = section(
                "s", "T", "<p>x</p>", bibleRefs = listOf("Sal 103:20", "Ef 4:8"),
            ).copy(objective = "Consolar", agreedApproach = "Tom pastoral"),
            subPoints = listOf("Jeová é organizado", "Os anjos o louvam"),
        )
        assertTrue(p.contains("Refs bíblicas: Sal 103:20, Ef 4:8"))
        assertTrue(p.contains("objetivo: Consolar"))
        assertTrue(p.contains("abordagem acordada: Tom pastoral"))
        assertTrue(p.contains("Subpontos:"))
        assertTrue(p.contains("1. Jeová é organizado"))
        assertTrue(p.contains("2. Os anjos o louvam"))
    }
}
