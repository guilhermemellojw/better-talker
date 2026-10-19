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

    @Test
    fun modo_aviso_detecta_mas_nao_remove() {
        val resposta = "O texto fala de esperança. \"Todos os justos herdarão a terra para sempre.\""
        val r = GroundednessVerifier.verify(resposta, fontes, warnOnly = true)
        assertTrue(r.hasRemovals)
        assertEquals(1, r.removed.size)
        // Texto intacto: sem corte e sem aviso de remoção.
        assertEquals(resposta, r.text)
        assertFalse(r.text.contains(GroundednessVerifier.REMOVAL_NOTICE))
    }

    @Test
    fun modo_aviso_sem_problema_nao_muda_nada() {
        val resposta = "O texto fala de esperança e consolo."
        val r = GroundednessVerifier.verify(resposta, fontes, warnOnly = true)
        assertFalse(r.hasRemovals)
        assertEquals(resposta, r.text)
    }

    // ---------- Bloco 1 (UX): remoção cirúrgica + pontuação limpa ----------

    @Test
    fun citacao_inventada_no_meio_da_frase_remove_so_a_citacao() {
        // Exemplo do pedido ("A vida é bela, "frase inventada", e o sol
        // brilha.") — com citação ≥ MIN_QUOTE_CHARS (20), como o gate exige.
        val resposta =
            "A vida é bela, \"uma frase totalmente inventada aqui\", e o sol brilha."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertTrue(r.hasRemovals)
        assertEquals(
            "A vida é bela, e o sol brilha.",
            r.text.removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).trim(),
        )
    }

    @Test
    fun remocao_no_inicio_nao_deixa_virgula_orfa() {
        val resposta = "\"Uma frase totalmente inventada aqui\", e o sol brilha."
        val r = GroundednessVerifier.verify(resposta, fontes)
        assertTrue(r.hasRemovals)
        assertEquals(
            "e o sol brilha.",
            r.text.removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).trim(),
        )
    }

    @Test
    fun limpeza_de_pontuacao_orfa() {
        // Costuras comuns depois da remoção: vírgula dupla, ",." e espaços.
        assertEquals(
            "A vida é bela, e o sol brilha.",
            GroundednessVerifier.cleanupAfterRemoval("A vida é bela, , e o sol brilha."),
        )
        assertEquals("Fim.", GroundednessVerifier.cleanupAfterRemoval("Fim, ."))
        assertEquals(
            "A vida é bela. E o sol brilha.",
            GroundednessVerifier.cleanupAfterRemoval("A vida é bela.  E o sol brilha."),
        )
        assertEquals("Ele diz.", GroundednessVerifier.cleanupAfterRemoval("Ele diz: ."))
    }

    // ---------- #verifier-join: não concatenar frases nas costuras ----------

    @Test
    fun remocaoDeCitacaoNaoConcatenaFrases() {
        val input = "A esperança é fuga. \"Esperança é ilusão, disse alguém.\" Mas a Bíblia promete."
        val r = GroundednessVerifier.verify(input, fontes)
        assertTrue(r.hasRemovals)
        val out = r.text.removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).trim()
        assertFalse(out.contains("fugaEsperança"))
        assertTrue(out.contains("fuga. Mas") || out.contains("fuga.  Mas"))
    }

    @Test
    fun remocaoDeCitacaoSemEspacoNaoColaPalavras() {
        // Caso real de modelo: fecha a frase e abre aspas/segue texto sem
        // espaço. A remoção não pode produzir "fuga.Mas".
        val input = "A esperança é fuga.\"Todos os justos herdarão a terra para sempre.\"Mas a Bíblia promete."
        val r = GroundednessVerifier.verify(input, fontes)
        assertTrue(r.hasRemovals)
        val out = r.text.removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).trim()
        assertFalse(out.contains("fuga.Mas"))
        assertTrue(out.contains("fuga. Mas"))
    }

    @Test
    fun remocaoDeFraseComReferenciaNaoConcatena() {
        val input =
            "A esperança é fuga.Como mostra a página 99, o tema é central.O próximo ponto é claro."
        val r = GroundednessVerifier.verify(input, fontes)
        assertTrue(r.hasRemovals)
        val out = r.text.removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).trim()
        assertFalse(out.contains("fuga.O"))
        assertTrue(out.contains("fuga. O"))
    }

    @Test
    fun citacaoComVersiculoSemEspacoNaoConcatena() {
        val input = "Como diz João 3:16, isso é claro." +
            "\"Deus amou tanto que enviou seu Filho para morrer por todos os pecados do mundo.\"" +
            "Isso confirma."
        val r = GroundednessVerifier.verify(input, fontes)
        assertTrue(r.hasRemovals)
        val out = r.text.removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).trim()
        assertFalse(out.contains("claro.Isso"))
        assertTrue(out.contains("claro. Isso"))
    }

    @Test
    fun limpeza_insere_espaco_apos_pontuacao_colada() {
        assertEquals(
            "A esperança é fuga. Mas a Bíblia promete.",
            GroundednessVerifier.cleanupAfterRemoval("A esperança é fuga.Mas a Bíblia promete."),
        )
        assertEquals(
            "Fim. O próximo.",
            GroundednessVerifier.cleanupAfterRemoval("Fim.O próximo."),
        )
    }
}
