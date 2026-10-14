package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #8c: limpeza DEFAULT da inserção — tags internas + preâmbulo/pós-escrito. */
class CleanForInsertTest {

    // ---------- GAP-3: tags internas ----------

    @Test
    fun decisao_parRemovidoConteudoPreservado() {
        assertEquals(
            "Texto final confirmado.",
            cleanForInsert("〈decisão〉Texto final confirmado.〈/decisão〉"),
        )
    }

    @Test
    fun sugestao_parRemovidoConteudoPreservado() {
        assertEquals(
            "Uma metáfora do rio.",
            cleanForInsert("〈sugestão〉Uma metáfora do rio.〈/sugestão〉"),
        )
    }

    @Test
    fun multiplasTags_removidas() {
        assertEquals(
            "um dois",
            cleanForInsert("〈decisão〉um〈/decisão〉 〈sugestão〉dois〈/sugestão〉"),
        )
    }

    @Test
    fun tagOrfa_removida() {
        assertEquals("texto limpo", cleanForInsert("texto limpo 〈/decisão〉"))
    }

    // ---------- GAP-2: preâmbulo/pós-escrito "meta" ----------

    @Test
    fun preambuloMaisDiscurso_soODiscurso() {
        assertEquals(
            "Conteúdo principal do card.",
            cleanForInsert("Claro! Aqui está o texto:\n\nConteúdo principal do card."),
        )
    }

    @Test
    fun discursoMaisPosEscrito_soODiscurso() {
        assertEquals(
            "Conteúdo principal do card.",
            cleanForInsert("Conteúdo principal do card.\n\nEspero ter ajudado!"),
        )
    }

    @Test
    fun preambuloEPosEscrito_soODiscurso() {
        assertEquals(
            "Conteúdo principal do card.",
            cleanForInsert(
                "Claro! Aqui vai:\n\nConteúdo principal do card.\n\nSe quiser, posso ajustar."
            ),
        )
    }

    @Test
    fun unicoParagrafoMeta_inalterado() {
        assertEquals("Claro!", cleanForInsert("Claro!"))
    }

    @Test
    fun paragrafoDoMeioComClaro_inalterado() {
        val text = "Primeiro parágrafo.\n\nClaro, o meio fica.\n\nÚltimo parágrafo."
        assertEquals(text, cleanForInsert(text))
    }

    @Test
    fun paragrafoIniciandoComCitacao_inalterado() {
        val text = "\"Claro que sim\", disse ele.\n\nConteúdo."
        val out = cleanForInsert(text)
        assertTrue(out, out.startsWith("\"Claro que sim\""))
    }
}
