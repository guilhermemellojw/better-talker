package com.bettertalker.app.data.export

import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 (Fase 2) — parser do Markdown canônico de "insira tudo na nota".
 * Puro/testável (sem Room/LLM).
 */
class StructureParserTest {

    private val canonico = """
        # INTRODUÇÃO
        Abertura com pergunta.

        # SEÇÃO 1: Esperança que sustenta
        Texto do ponto com **negrito**.

        # CONCLUSÃO
        Fechamento curto.
    """.trimIndent()

    private var ids = 0
    private fun conv(sections: List<StructureSection>, noteId: String = "n1") =
        toOutlineConversion(sections, noteId, "Tema", idProvider = { "id-${ids++}" }, now = { 1000L })

    private fun section(
        role: SectionRole,
        order: Int,
        id: String = "s$order",
    ) = SpeechSection(
        id = id, noteId = "n1", order = order, role = role,
        title = "T$order", minutes = 1, contentHtml = "",
        bibleRefs = emptyList(), publicationRefs = emptyList(),
        methodPrinciple = null, createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun parseCanonicoExtraiTresPartes() {
        val secs = parseStructureMarkdown(canonico)
        assertEquals(3, secs.size)
        assertEquals(StructureRole.INTRO, secs[0].role)
        assertEquals("INTRODUÇÃO", secs[0].title)
        assertEquals(StructureRole.BODY, secs[1].role)
        assertEquals("Esperança que sustenta", secs[1].title)
        assertEquals(StructureRole.CONCLUSION, secs[2].role)
        assertTrue(secs[1].contentHtml.contains("<strong>negrito</strong>"))
    }

    @Test
    fun parseToleranteCaixaENivel() {
        val secs = parseStructureMarkdown("## secao 2: Fé\nTexto.\n\n# conclusao\nFim.")
        assertEquals(2, secs.size)
        assertEquals(StructureRole.BODY, secs[0].role)
        assertEquals("Fé", secs[0].title)
        assertEquals(StructureRole.CONCLUSION, secs[1].role)
    }

    @Test
    fun parseSemCabecalho_retornaVazio() {
        assertTrue(parseStructureMarkdown("só texto corrido, sem estrutura.").isEmpty())
        assertTrue(parseStructureMarkdown("").isEmpty())
    }

    @Test
    fun markdownEscapaHtml() {
        val html = markdownToSimpleHtml("5 < 6 & 7 > 4")
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.startsWith("<p>"))
    }

    @Test
    fun instrucaoCanonicaTemFormatoParseavel() {
        // O que o prompt pede, o parser entende (contratoInstruction↔parser).
        val instruction = com.bettertalker.app.data.copilot.APPLY_STRUCTURE_INSTRUCTION
        assertTrue(instruction.contains("# INTRODUÇÃO"))
        assertTrue(instruction.contains("# SEÇÃO 1"))
        assertTrue(instruction.contains("# CONCLUSÃO"))
        val sample = "# INTRODUÇÃO\nOi.\n\n# SEÇÃO 1: X\nY.\n\n# CONCLUSÃO\nFim."
        assertEquals(3, parseStructureMarkdown(sample).size)
    }

    @Test
    fun conversaoTemPapeisEOrdens() {
        val c = conv(parseStructureMarkdown(canonico))
        assertEquals("Tema", c.noteTitle)
        assertEquals(DiscourseType.S34_DISCOURSE, c.discourseType)
        assertEquals(SectionRole.INTRO, c.intro?.role)
        assertEquals(0, c.intro?.order)
        assertEquals(listOf(1), c.bodies.map { it.section.order })
        assertEquals(SectionRole.BODY, c.bodies.single().section.role)
        assertEquals(2, c.conclusion?.order)
        assertEquals(SectionRole.CONCLUSION, c.conclusion?.role)
        // Corpo vira sub-pontos (convenção do domínio), intro/conclusão levam HTML.
        assertTrue(c.bodies.single().subPoints.isNotEmpty())
        assertTrue(c.intro?.contentHtml?.contains("Abertura") == true)
    }

    @Test
    fun mergeAppend_preservaExistentesEAnexa() {
        val existing = listOf(section(SectionRole.INTRO, 0, "a"), section(SectionRole.BODY, 1, "b"))
        val sub = SubPoint(
            id = "sp", sectionId = "b", order = 0, outlineText = "ideia",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            instruction = null, developedHtml = "", createdAt = 0L, updatedAt = 0L,
        )
        val fresh = conv(parseStructureMarkdown("# SEÇÃO 1: Novo\nTexto novo."))
        val merged = mergeAppend(existing, mapOf("b" to listOf(sub)), fresh)
        // Intro existente prevalece; bodies antigos + novo; orders renormalizados.
        assertEquals("a", merged.intro?.id)
        assertEquals(listOf("b", "id-0"), merged.bodies.map { it.section.id })
        val orders = listOf(merged.intro!!.order) +
            merged.bodies.map { it.section.order } +
            listOfNotNull(merged.conclusion?.order)
        assertEquals(orders.sorted(), orders)
        assertEquals(listOf(sub), merged.bodies.first { it.section.id == "b" }.subPoints)
        assertTrue(merged.bodies.any { it.subPoints.any { sp -> sp.outlineText == "Texto novo." } })
    }

    @Test
    fun mergeAppend_introNovaQuandoNaoHa() {
        val existing = listOf(section(SectionRole.BODY, 0, "b"))
        val fresh = conv(parseStructureMarkdown("# INTRODUÇÃO\nOi.\n\n# SEÇÃO 1: X\nY."))
        val merged = mergeAppend(existing, emptyMap(), fresh)
        assertEquals("INTRODUÇÃO", merged.intro?.title)
        assertEquals(0, merged.intro?.order)
        assertEquals(1, merged.bodies.first { it.section.id == "b" }.section.order)
    }

    @Test
    fun mergeAppend_conclusionExistentePrevalece() {
        val existing = listOf(
            section(SectionRole.BODY, 0, "b"),
            section(SectionRole.CONCLUSION, 1, "c"),
        )
        val fresh = conv(parseStructureMarkdown("# CONCLUSÃO\nNova.\n\n# SEÇÃO 1: X\nY."))
        val merged = mergeAppend(existing, emptyMap(), fresh)
        assertEquals("c", merged.conclusion?.id)
        assertNull(merged.bodies.firstOrNull { it.section.title == "Conclusão" })
    }
}
