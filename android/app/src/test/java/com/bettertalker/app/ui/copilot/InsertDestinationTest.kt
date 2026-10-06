package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.db.SpeechSectionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T5 (Mini Discurso): destinos do seletor de inserção (tópicos/legado). */
class InsertDestinationTest {

    private fun sec(id: String, role: String, title: String, minutes: Int, order: Int) =
        SpeechSectionEntity(
            id = id, noteId = "n", order = order, role = role, title = title,
            minutes = minutes, contentHtml = "", bibleRefsJson = "[]",
            publicationRefsJson = "[]", methodPrinciple = null,
            createdAt = 0L, updatedAt = 0L,
        )

    @Test
    fun topicDestinations_soBodyNaOrdemComDuracao() {
        val dests = topicDestinations(listOf(
            sec("c", "CONCLUSION", "Conclusão", 1, 3),
            sec("b2", "BODY", "Segundo ponto", 4, 1),
            sec("i", "INTRO", "Introdução", 1, 0),
            sec("b1", "BODY", "Primeiro ponto", 6, 0),
        ))
        assertEquals(
            listOf("Primeiro ponto · 6 min", "Segundo ponto · 4 min"),
            dests.map { it.label },
        )
        assertEquals(listOf("b1", "b2"), dests.map { it.sectionId })
    }

    @Test
    fun insertionDestinations_topicosVencemHeadingsLegados() {
        val topics = listOf(InsertDestination("Tópico · 5 min", sectionId = "s1"))
        val r = insertionDestinations(topics, listOf("Heading antigo"))
        assertEquals(topics, r)
    }

    @Test
    fun insertionDestinations_semTopicosUsaHeadingsLegados() {
        val r = insertionDestinations(emptyList(), listOf("Introdução", "Ponto 1"))
        assertEquals(listOf("Introdução", "Ponto 1"), r.map { it.label })
        assertEquals(listOf("Introdução", "Ponto 1"), r.map { it.heading })
        assertTrue(r.all { it.sectionId == null })
    }
}
