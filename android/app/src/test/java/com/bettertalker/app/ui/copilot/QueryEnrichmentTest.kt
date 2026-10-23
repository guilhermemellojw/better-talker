package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #15d — query do RAG enriquecida. Pura, sem Android. */
class QueryEnrichmentTest {

    @Test
    fun comSecaoEmFoco_queryContemTituloDaSecao() {
        val q = enrichedRetrievalQuery(
            blockText = "prosa da seção",
            question = "desenvolva",
            sectionTitle = "Como agir com fé",
        )
        assertTrue(q.startsWith("Como agir com fé"))
        assertTrue(q.contains("prosa da seção"))
        assertTrue(q.endsWith("desenvolva"))
    }

    @Test
    fun comNotaES34_queryContemTitulos() {
        val q = enrichedRetrievalQuery(
            blockText = "prosa",
            question = "pergunta",
            sectionTitle = "Fé que age",
            noteTitle = "MEU DISCURSO",
            s34SectionTitles = listOf("Introdução", "Conclusão"),
        )
        assertTrue(q.contains("Fé que age"))
        assertTrue(q.contains("MEU DISCURSO"))
        assertTrue(q.contains("Introdução"))
        assertTrue(q.contains("Conclusão"))
    }

    @Test
    fun semContexto_mantemComportamentoAntigo() {
        assertEquals(
            "prosa pergunta",
            enrichedRetrievalQuery(blockText = "prosa", question = "pergunta"),
        )
    }

    @Test
    fun teto_naoPassaDe2500() {
        val q = enrichedRetrievalQuery(
            blockText = "x".repeat(3000),
            question = "y",
            sectionTitle = "S",
        )
        assertEquals(2500, q.length)
    }

    @Test
    fun citacoesEntramNaQuery() {
        val q = enrichedRetrievalQuery(
            blockText = "prosa",
            question = "o que diz?",
            citedRefs = listOf("w25.07 22 §§ 8-9", "it \"Gedalias\" §4"),
        )
        assertTrue(q.contains("w25.07 22 §§ 8-9"))
        assertTrue(q.contains("it \"Gedalias\" §4"))
    }
}
