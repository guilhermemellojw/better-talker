package com.bettertalker.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #15c — verificador tolerante: normalização simétrica e robusta nos dois
 * lados (travessões, NBSP, aspas curvas, reticências) sem afrouxar a
 * remoção de citação realmente inventada. Puro, sem Android.
 */
class GroundednessNormalizationTest {

    private val corpus = listOf(
        "Jesus — o Filho de Deus — veio à Terra para salvar a humanidade.",
        "Jeová sustenta seus servos fiéis com amor leal e bondade.",
    )

    @Test
    fun citacaoLiteral_comTravessoesTrocadas_eMantida() {
        // O modelo pode trocar — por -; a citação legítima não é removida.
        val resposta =
            "A fonte diz: \"Jesus - o Filho de Deus - veio à Terra para salvar a humanidade.\""
        val r = GroundednessVerifier.verify(resposta, corpus)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun citacaoComEspacosExtras_eMantida() {
        val resposta =
            "A fonte diz: \"Jeová  sustenta   seus servos fiéis com amor leal e bondade.\""
        val r = GroundednessVerifier.verify(resposta, corpus)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun citacaoComEspacoInquebravelNoCorpus_eMantida() {
        val fontes = listOf("Jeová\u00A0sustenta seus servos fiéis com amor leal e bondade.")
        val resposta =
            "A fonte diz: \"Jeová sustenta seus servos fiéis com amor leal e bondade.\""
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun citacaoComAspasCurvasNoCorpus_eMantida() {
        val fontes = listOf("Ele disse: “venha e seja minha testemunha fiel até o fim”.")
        val resposta = "A fonte diz: \"venha e seja minha testemunha fiel até o fim\"."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun citacaoRealmenteInventada_eRemovida() {
        val resposta =
            "O texto promete: \"todos os maus serão destruídos amanhã cedo sem falta\"."
        val r = GroundednessVerifier.verify(resposta, corpus)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("serão destruídos amanhã"))
    }

    @Test
    fun normalize_tipografia_unificada() {
        assertEquals("jesus-o filho", GroundednessVerifier.normalize("Jesus — o Filho"))
        assertEquals("a b", GroundednessVerifier.normalize("a\u00A0b"))
        assertEquals("fim...", GroundednessVerifier.normalize("fim…"))
        assertEquals("nao 'e' assim", GroundednessVerifier.normalize("não ‘é’ assim"))
    }
}
