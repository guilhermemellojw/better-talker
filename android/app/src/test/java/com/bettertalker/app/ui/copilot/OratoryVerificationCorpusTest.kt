package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16-fix-d — o corpus do gate cobre as citações autorizadas: com o texto
 * da ref no corpus a citação legítima passa; sem ele, continua removida.
 * Puro/testável.
 */
class OratoryVerificationCorpusTest {

    private val verso =
        "Louvem a Jeová, todos os seus anjos, fortes e poderosos, que cumprem a sua palavra, obedecendo ao que ele diz."
    private val texto = "O Salmo 103:20 diz: \u201C$verso\u201D Fim do trecho."

    @Test
    fun citacaoAutorizadaNaoEhRemovida() {
        // Fonte no formato real do corpus: "[ref] texto" (questionVerseLines).
        val r = verifyGeneratedText(texto, null, listOf("[Salmo 103:20] $verso"))
        assertFalse(r.hasRemovals)
        assertTrue(r.text.contains("Louvem a Jeová"))
    }

    @Test
    fun semTextoDaRefCitacaoEhRemovida() {
        val r = verifyGeneratedText(texto, null, listOf("Outro texto qualquer, sem o versículo."))
        assertTrue(r.hasRemovals)
    }

    @Test
    fun citacaoInventadaContinuaRemovida() {
        val inventada =
            "O Salmo 103:20 diz: \u201CEu vi mil cavalos de fogo correndo pelo vale secreto de Quima.\u201D Fim."
        val r = verifyGeneratedText(inventada, null, listOf("[Salmo 103:20] $verso"))
        assertTrue(r.hasRemovals)
    }
}
