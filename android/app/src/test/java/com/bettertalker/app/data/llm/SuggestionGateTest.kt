package com.bettertalker.app.data.llm

import com.bettertalker.app.data.ai.checkCitations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2 — whitelist do marcador 〈sugestão〉 no gate (puro, sem Android). */
class SuggestionGateTest {

    private val fontes = listOf(
        "Gên 1:26 Deus criou os humanos para viver uma vida perfeita, eterna, na Terra."
    )

    // ---------- helper ----------

    @Test
    fun spansEInSuggestion() {
        val t = "antes 〈sugestão〉criação aqui〈/sugestão〉 depois"
        val spans = suggestionSpans(t)
        assertEquals(1, spans.size)
        assertTrue(inSuggestion(t.indexOf("criação"), spans))
        assertFalse(inSuggestion(0, spans))
        assertFalse(inSuggestion(t.indexOf("depois"), spans))
    }

    // ---------- 1. prosa marcada é isenta ----------

    @Test
    fun prosaMarcadaIsentaNoVerificador() {
        val resp = "〈sugestão〉Imagine um rio que nunca seca atravessando o deserto de ponta a ponta.〈/sugestão〉"
        val r = GroundednessVerifier.verify(resp, fontes)
        assertFalse("prosa marcada não deve ser removida", r.hasRemovals)
    }

    @Test
    fun aspasLongasMarcadasSaoIsentasNoCitationCheck() {
        val resp = "〈sugestão〉“uma frase longa e completamente inventada sobre o tema marcado”〈/sugestão〉"
        assertTrue(checkCitations(resp, fontes).ok)
    }

    // ---------- 2. versículo inventado NÃO é isento ----------

    @Test
    fun versiculoInventadoEmCriacaoAindaAvisa() {
        val resp = "〈sugestão〉Como Gên 99:99 diz, siga firme.〈/sugestão〉"
        val r = GroundednessVerifier.verify(resp, fontes)
        assertTrue("versículo inventado deve ser removido", r.hasRemovals)
        assertFalse(r.text.contains("Gên 99:99"))
        assertFalse(checkCitations(resp, fontes).ok)
    }

    // ---------- 3. número inventado NÃO é isento ----------

    @Test
    fun numeroInventadoEmCriacaoAindaAvisa() {
        val resp = "〈sugestão〉Na aldeia de Betel, 73% das pessoas já ouviram isso.〈/sugestão〉"
        val r = GroundednessVerifier.verify(resp, fontes)
        assertTrue("número inventado deve ser removido", r.hasRemovals)
        assertFalse(r.text.contains("73%"))
    }

    // ---------- 4. fato sem marcador continua gate normal ----------

    @Test
    fun fatoSemMarcadorContinuaGateNormal() {
        val resp = "Todos os estudiosos confirmam “uma frase longa e completamente inventada sobre o tema” no texto."
        val r = GroundednessVerifier.verify(resp, fontes)
        assertTrue(r.hasRemovals)
    }
}
