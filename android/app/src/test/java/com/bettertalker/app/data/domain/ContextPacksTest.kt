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
}
