package com.bettertalker.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exp/mentoria-e-verifier (2026-10-21) — falso positivo do verifier.
 *
 * Caso real (A/B Copilot vs Gemini, A34): o app citava o §8-9 LITERAL da
 * w25.07 ("Baruque, o secretário do profeta Jeremias…") e o verifier acusava
 * "Revise: 1 citação" — porque o acervo é indexado por FRASE (uma citação
 * atravessa duas entradas) e a pontuação original ("despojo'.") não
 * batia caractere a caractere.
 */
class GroundednessQuoteNormalizationTest {

    private val corpus = listOf(
        "Em que armadilha Baruque caiu",
        "Baruque, o secretário do profeta Jeremias, tinha uma designação difícil: ajudar Jeremias a " +
            "transmitir uma forte mensagem para uma nação ingrata",
        "Mas depois de um tempo, Baruque acabou caindo numa armadilha Ele ficou distraído",
    )

    @Test
    fun normalize_remove_pontuacao() {
        val n = GroundednessVerifier.normalizeForComparison("despojo'. \"Fique\" — assim!")
        assertEquals("despojo fique assim", n)
    }

    @Test
    fun normalize_colapsa_espacos() {
        val n = GroundednessVerifier.normalizeForComparison("  Jeremias   45:3  \n firmeza ")
        assertEquals("jeremias 45 3 firmeza", n)
    }

    @Test
    fun normalize_mantem_as_letras() {
        // "Deus" e "Jeová" NÃO podem virar a mesma coisa (precisão doutrinária).
        assertFalse(
            GroundednessVerifier.normalizeForComparison("Deus") ==
                GroundednessVerifier.normalizeForComparison("Jeová")
        )
    }

    @Test
    fun verifier_cita_literal_que_atravessa_dois_trechos_nao_sinaliza() {
        val quote = "\"Em que armadilha Baruque caiu Baruque, o secretário do profeta Jeremias, tinha uma " +
            "designação difícil: ajudar Jeremias a transmitir uma forte mensagem para uma nação ingrata\""
        val res = GroundednessVerifier.verify(quote, corpus, warnOnly = true)
        assertEquals("citação literal não deve ser sinalizada", 0, res.metrics.total)
    }

    @Test
    fun verifier_cita_com_pontuacao_diferente_nao_sinaliza() {
        // No acervo: "...como despojo'." — o modelo cita sem o apóstrofo.
        val c = listOf(
            "Mas você está procurando grandes coisas para si. Pare de procurar essas coisas.",
            "e, aonde quer que você for, eu lhe darei a sua vida como despojo'.",
        )
        val quote = "\"aonde quer que você for, eu lhe darei a sua vida como despojo.\""
        val res = GroundednessVerifier.verify(quote, c, warnOnly = true)
        assertEquals("citação com pontuação diferente não deve ser sinalizada", 0, res.metrics.total)
    }

    @Test
    fun verifier_cita_inventada_continua_sinalizando() {
        // REGRESSÃO: citação que não existe em lugar nenhum continua sinalizada.
        val quote = "\"Baruque era um profeta de Jeová que ganhou o mundo pela fé\""
        val res = GroundednessVerifier.verify(quote, corpus, warnOnly = true)
        assertTrue("citação inventada deve ser sinalizada", res.metrics.hasFlags)
        assertTrue(res.removed.any { it.contains("profeta") })
    }

    @Test
    fun verifier_hipotese_do_usuario_nao_e_invencao() {
        // A hipótese do usuário ("Baruque perdeu prestígio social") entra no
        // corpus (a fala do próprio usuário é fonte) → não é invenção.
        val c = corpus + "Baruque perdeu prestígio social"
        val quote = "\"Baruque perdeu prestígio social\""
        val res = GroundednessVerifier.verify(quote, c, warnOnly = true)
        assertEquals(0, res.metrics.total)
    }
}
