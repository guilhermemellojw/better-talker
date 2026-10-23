package com.bettertalker.app.data.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPacksTest {

    private fun cited() = EvidenceSource(
        id = "cite-w25-8",
        reference = "w25.07 22 §§ 8-9",
        text = "parágrafo citado",
        sourceType = SourceType.CONTENT,
        publication = "A Sentinela",
        section = null,
        paragraph = 8,
        page = 22,
    )

    @Test
    fun citadasEntramAntesDoTopKESemTextoViraReferencia() {
        val pack = ContextPacks.fromCandidates(
            content = emptyList(),
            training = emptyList(),
            pinned = listOf(cited()),
            unavailableRefs = listOf("it \"Gedalias\" §4"),
        )
        assertEquals(1, pack.contentSources.size)
        assertEquals("cite-w25-8", pack.contentSources.first().id)
        assertEquals(listOf("it \"Gedalias\" §4"), pack.unavailableRefs)
        assertTrue(pack.contentSources.first().text.contains("parágrafo citado"))
    }

    @Test
    fun excedenteDeCitadasViraReferenciaForaDoOrcamento() {
        val pinned = (1..10).map {
            cited().copy(id = "cite-$it", reference = "w25.07 §$it")
        }
        val pack = ContextPacks.fromCandidates(
            content = emptyList(),
            training = emptyList(),
            contentLimit = 8,
            pinned = pinned,
        )
        assertEquals(8, pack.contentSources.size)
        assertEquals(listOf("w25.07 §9", "w25.07 §10"), pack.omittedRefs)
    }
}
