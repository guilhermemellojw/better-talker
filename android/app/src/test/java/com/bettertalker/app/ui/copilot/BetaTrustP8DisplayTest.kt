package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.bible.displayBibleRefsFor
import com.bettertalker.app.data.copilot.ContentEligibilityGate
import com.bettertalker.app.data.copilot.SourceFidelityGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BETA-TRUST/P8 — display canônico de Salmos e alias inválido. */
class BetaTrustP8DisplayTest {

    private val corpus = listOf("Salmo 103:20 — Louvem a Jeová, todos os seus anjos.")

    @Test
    fun aliasSalpoNaoDeveSerAceitoComoSalmos() {
        val d = SourceFidelityGate.enforce("Salpo 103:20 — texto", corpus)
        assertTrue("grafia inválida não pode herdar autoridade de Salmos", d.blocked)
    }

    @Test
    fun displayDeSalmosUsaNomeCanonico() {
        val shown = displayBibleRefsFor("Salmo 103:20")
        assertTrue("display=$shown", shown.contains("Salmos 103:20"))
        assertFalse(shown.contains("Salpo"))
    }

    @Test
    fun versiculoIncorretoEhRejeitado() {
        val d = SourceFidelityGate.enforce("Salmo 103:21 — outro versículo", corpus)
        assertTrue(d.blocked)
    }

    @Test
    fun citacaoLiteralSemApoioEhRejeitada() {
        val e = ContentEligibilityGate.forFinalAnswer(
            "Salmo 103:20 diz: “inventado sem fonte nenhuma”.", corpus, emptyList(),
        )
        assertFalse(e.allowed)
    }

    @Test
    fun referenciaValidaEhPreservada() {
        val e = ContentEligibilityGate.forFinalAnswer(
            "Salmo 103:20 — Louvem a Jeová, todos os seus anjos.", corpus, emptyList(),
        )
        assertTrue(e.allowed)
        assertTrue(e.text.contains("Louvem a Jeová"))
    }
}
