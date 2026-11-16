package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OratoryFidelityCleanupTest {

    @Test
    fun removeSentencaComRefInventada() {
        val text = "Adão e Eva desobedeceram (Gênesis 3:6). " +
            "Apocalipse 7:9 mostra a multidão. O propósito de Jeová não mudou."
        val report = OratoryFidelityCheck.Report(
            inventedReferences = listOf("Apocalipse 7:9"),
            leakedReferences = emptyList(),
            unsupportedNumbers = emptyList(),
        )
        assertEquals(
            "Adão e Eva desobedeceram (Gênesis 3:6). O propósito de Jeová não mudou.",
            removeUnsupportedFidelitySentences(text, report),
        )
    }

    @Test
    fun removeRefVazadaDeOutroPonto() {
        val text = "O ponto 2 termina aqui. Como Tiago 2:17 mostra, a fé age."
        val report = OratoryFidelityCheck.Report(
            inventedReferences = emptyList(),
            leakedReferences = listOf("Tiago 2:17"),
            unsupportedNumbers = emptyList(),
        )
        assertEquals(
            "O ponto 2 termina aqui.",
            removeUnsupportedFidelitySentences(text, report),
        )
    }

    @Test
    fun removeNumeroSemApoio() {
        val text = "A vida dura 120 anos. O propósito de Jeová não mudou."
        val report = OratoryFidelityCheck.Report(
            inventedReferences = emptyList(),
            leakedReferences = emptyList(),
            unsupportedNumbers = listOf("120"),
        )
        assertEquals(
            "O propósito de Jeová não mudou.",
            removeUnsupportedFidelitySentences(text, report),
        )
    }

    @Test
    fun preservaTextoQuandoNaoHaOfensa() {
        val text = "Gênesis 3:6 explica a queda.\n\nJeová prometeu restaurar."
        val report = OratoryFidelityCheck.Report(emptyList(), emptyList(), emptyList())
        assertEquals(text, removeUnsupportedFidelitySentences(text, report))
    }

    @Test
    fun preservaParagrafosERemoveSoALinhaOfensora() {
        val text = "Primeiro parágrafo.\n\nSegundo cita Apocalipse 20:13.\n\nTerceiro."
        val report = OratoryFidelityCheck.Report(
            inventedReferences = listOf("Apocalipse 20:13"),
            leakedReferences = emptyList(),
            unsupportedNumbers = emptyList(),
        )
        val cleaned = removeUnsupportedFidelitySentences(text, report)
        assertTrue(cleaned.contains("Primeiro parágrafo."))
        assertTrue(cleaned.contains("Terceiro."))
        assertTrue(!cleaned.contains("Apocalipse"))
        assertTrue(cleaned.contains("\n\n"))
    }
}
