package com.bettertalker.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F2 — verificador de apoio factual (conservador). Puro, sem Android. */
class GroundednessVerifierTest {

    private val fontes = listOf(
        "Gên 1:26 Deus criou os humanos para viver uma vida perfeita, eterna, na Terra.",
        "S-34, página 12 Milhões vão sobreviver ao fim deste atual sistema; outros bilhões vão ser ressuscitados.",
    )

    @Test
    fun citacao_presente_nas_fontes_e_mantida() {
        val resposta = "A fonte diz: \"Deus criou os humanos para viver uma vida perfeita, eterna, na Terra.\""
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
        assertEquals(resposta, r.text)
    }

    @Test
    fun citacao_ausente_remove_so_a_frase_e_avisa() {
        val resposta = "O texto fala de esperança. \"Todos os justos herdarão a terra para sempre.\" " +
            "No fim, há consolo."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertTrue(r.hasRemovals)
        assertEquals(1, r.removed.size)
        assertFalse(r.text.contains("herdarão a terra"))
        assertTrue(r.text.contains("O texto fala de esperança."))
        assertTrue(r.text.contains("No fim, há consolo."))
        assertTrue(r.text.endsWith(GroundednessVerifier.REMOVAL_NOTICE))
    }

    @Test
    fun referencia_de_pagina_ausente_remove_a_frase() {
        val resposta = "Como mostra a página 99, o tema é central."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("página 99"))
    }

    @Test
    fun referencia_de_pagina_presente_e_mantida() {
        val resposta = "Na página 12, a fonte fala dos sobreviventes."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun citacao_curta_e_ignorada() {
        val resposta = "Ele disse \"sim\" e seguiu."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
    }

    // ---------- T1 (polimento visual): markdown no gate ----------

    @Test
    fun negrito_dentro_de_citacao_valida_e_mantido() {
        // O modelo pode enfatizar DENTRO das aspas; o gate não pode podar.
        val resposta =
            "A fonte diz: \"**Deus criou os humanos para viver uma vida perfeita, eterna, na Terra.**\""
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
        assertEquals(resposta, r.text)
    }

    @Test
    fun negrito_em_citacao_inventada_e_removido() {
        val resposta = "O texto promete: \"**Todos os justos herdarão a terra para sempre.**\" Fim."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertTrue(r.hasRemovals)
        assertFalse(r.text.contains("herdarão a terra"))
    }

    @Test
    fun normalize_remove_marcas_de_markdown() {
        assertEquals("gen 1:26", GroundednessVerifier.normalize("**Gên 1:26**"))
        assertEquals("titulo da secao", GroundednessVerifier.normalize("## Título da seção"))
        assertEquals("citacao em bloco", GroundednessVerifier.normalize("> citação em bloco"))
    }

    @Test
    fun negrito_no_versiculo_valido_nao_gera_falso_positivo() {
        val resposta = "Como **Gên 1:26** mostra, fomos criados para viver para sempre."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun sem_fontes_nao_altera() {
        val resposta = "\"Qualquer coisa inventada aqui\" fica como está."
        val r = GroundednessVerifier.verify(resposta, emptyList())
        assertFalse(r.hasRemovals)
        assertEquals(resposta, r.text)
    }

    @Test
    fun acentos_e_caixa_nao_importam_na_comparacao() {
        val resposta = "A citação: \"deus criou os humanos para viver uma vida perfeita, eterna, na terra.\""
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
    }

    @Test
    fun aspas_tipograficas_sao_detectadas() {
        val resposta = "Ele diz: “todos os justos herdarão a terra para sempre”."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertTrue(r.hasRemovals)
    }

    @Test
    fun criatividade_sem_citacao_nem_referencia_e_preservada() {
        val resposta = "Uma ilustração possível seria comparar com um rio que nunca seca. " +
            "Essa imagem pode ajudar a explicar a ideia com linguagem simples."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertFalse(r.hasRemovals)
        assertEquals(resposta, r.text)
    }
}
