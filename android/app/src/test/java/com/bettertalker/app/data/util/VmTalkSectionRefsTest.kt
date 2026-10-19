package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #24-fix (d) — a seção TALK nasce com as refs do corpo: o dossiê do chat
 * sobre a seção já funciona antes de aplicar a estrutura.
 */
class VmTalkSectionRefsTest {

    private fun part(body: String) = VmPart(
        section = VmSection.TREASURES,
        partNumber = 1,
        title = "Ter esperança é o segredo para o contentamento",
        minutes = 10,
        nature = VmNature.TALK,
        body = body,
    )

    @Test
    fun talkSection_populaRefsBiblicasDoCorpo() {
        val section = vmPartSection(
            part("Tópico 1: Parece que Baruque não estava satisfeito. (Jer. 45:3)"),
            noteId = "n1", order = 0, id = "s1", now = 1L,
        )
        assertTrue(section.bibleRefs.contains("Je 45:3"))
    }

    @Test
    fun talkSection_populaRefsDePublicacaoDoCorpo() {
        val section = vmPartSection(
            part("Tópico 1: Parece que Baruque não estava satisfeito. (w25.07 22 §§ 8-9)"),
            noteId = "n1", order = 0, id = "s1", now = 1L,
        )
        val pub = section.publicationRefs.single()
        assertEquals("w25.07", pub.symbol)
        assertEquals(22, pub.page)
        assertEquals(8, pub.paragraph)
    }

    @Test
    fun talkSection_semRefsNoCorpo_ficaVazia() {
        val section = vmPartSection(
            part("Corpo sem referências."),
            noteId = "n1", order = 0, id = "s1", now = 1L,
        )
        assertTrue(section.bibleRefs.isEmpty())
        assertTrue(section.publicationRefs.isEmpty())
    }

    @Test
    fun extractRefsFromBody_canonizaBibliaEPublicacao() {
        val (bible, pubs) = extractRefsFromBody("Jer. 45:3 e Jer. 45:5; w25.07 20 § 2")
        assertEquals(listOf("Je 45:3", "Je 45:5"), bible)
        assertEquals(1, pubs.size)
        assertEquals("w25.07", pubs.single().symbol)
        assertEquals(20, pubs.single().page)
        assertEquals(2, pubs.single().paragraph)
    }
}
