package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationContractTest {

    @Test
    fun aceitaFonteDentroDoIntervalo() {
        val check = CitationContract.check("Segundo a fonte, os anjos servem a Deus [Fonte 1].", 2)
        assertTrue(check.ok)
        assertEquals(listOf(1), check.cited)
    }

    @Test
    fun rejeitaFonteForaDoIntervalo() {
        val check = CitationContract.check("Veja [Fonte 3].", 2)
        assertFalse(check.ok)
        assertTrue(check.violations.any { it.contains("fora do intervalo") })
    }

    @Test
    fun exigeCitacaoQuandoHaFontes() {
        val check = CitationContract.check("Os anjos são mensageiros.", 1)
        assertFalse(check.ok)
        assertTrue(check.violations.any { it.contains("sem [Fonte N]") })
    }

    @Test
    fun permiteRespostaSemCitacaoQuandoNaoHaFonte() {
        val check = CitationContract.check("Use uma pergunta na introdução.", 0)
        assertTrue(check.ok)
    }

    @Test
    fun proibeFalsaRecusaComFonteDisponivel() {
        val check = CitationContract.check(
            "Não tenho acesso a essa publicação, mas posso ajudar com oratória. [Fonte 1]",
            1,
        )
        assertFalse(check.ok)
        assertTrue(check.violations.any { it.contains("falta de acesso") })
    }

    @Test
    fun removeMarcadoresAntesDoTts() {
        val clean = CitationContract.stripSourceMarkers(
            "Os anjos servem a Deus [Fonte 1]. Veja também [Fonte 2].",
        )
        assertEquals("Os anjos servem a Deus. Veja também.", clean)
    }
}
