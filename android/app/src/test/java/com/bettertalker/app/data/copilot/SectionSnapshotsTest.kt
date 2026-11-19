package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1C.2.2A — o snapshot compartilhado mantém o hash do aceite B1. */
class SectionSnapshotsTest {

    private fun section(
        id: String = "s1",
        title: String = "Ponto 1",
        html: String = "<p>texto</p>",
        order: Int = 2,
    ) = SpeechSection(
        id = id,
        noteId = "n1",
        order = order,
        role = SectionRole.BODY,
        title = title,
        minutes = 5,
        contentHtml = html,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        methodPrinciple = null,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun hashReutilizaBlocoTituloConteudoDoAceite() {
        val section = section(title = "Ponto 1", html = "<p>texto</p>")

        assertEquals(
            hashText("Ponto 1\n\n<p>texto</p>"),
            section.toSectionSnapshot().contentHash,
        )
    }

    @Test
    fun conteudoVazioUsaSomenteTitulo() {
        val section = section(title = "Ponto 1", html = "")

        assertEquals(
            hashText("Ponto 1"),
            section.toSectionSnapshot().contentHash,
        )
    }

    @Test
    fun tituloVazioMantemFallbackVisualSemAlterarHash() {
        val section = section(title = "", html = "")

        val snapshot = section.toSectionSnapshot()

        assertEquals("Seção", snapshot.title)
        assertEquals(hashText(""), snapshot.contentHash)
    }

    @Test
    fun metadadosSaoCopiados() {
        val section = section(id = "s9", order = 4)

        val snapshot = section.toSectionSnapshot()

        assertEquals("s9", snapshot.id)
        assertEquals("n1", snapshot.noteId)
        assertEquals(SectionRole.BODY, snapshot.role)
        assertEquals(4, snapshot.order)
    }
}
