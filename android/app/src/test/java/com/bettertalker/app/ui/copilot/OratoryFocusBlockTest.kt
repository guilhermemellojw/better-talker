package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16-fix-b — bloco do foco da oratória: o LLM recebe a prosa da seção
 * (título + contentHtml), não só o título. Puro, sem Android.
 */
class OratoryFocusBlockTest {

    @Test
    fun secaoComConteudo_blocoTrazTituloEProsa() {
        val b = oratoryFocusBlock(
            sectionTitle = "JEOVÁ É UM DEUS DE ORGANIZAÇÃO",
            sectionContentHtml = "<p>Você já olhou para o céu?</p><p>Jeová valoriza a ordem.</p>",
            fallbackFocus = "JEOVÁ É UM DEUS DE ORGANIZAÇÃO",
        )
        assertEquals(
            "JEOVÁ É UM DEUS DE ORGANIZAÇÃO\n\n<p>Você já olhou para o céu?</p><p>Jeová valoriza a ordem.</p>",
            b,
        )
    }

    @Test
    fun secaoVazia_blocoEhSoOTitulo() {
        assertEquals(
            "Novo ponto",
            oratoryFocusBlock("Novo ponto", "", "Novo ponto"),
        )
    }

    @Test
    fun semSecao_mantemOFocoResolvido() {
        assertEquals(
            "Corpo da nota",
            oratoryFocusBlock(null, null, "Corpo da nota"),
        )
    }

    @Test
    fun blocoGeradoSatisfazAInvarianteDoAceite() {
        // O aceite exige `sectionProposalBlock(title, contentHtml).contains(focus)`.
        val title = "Título da seção"
        val html = "<p>Prosa da seção.</p>"
        val block = sectionProposalBlock(title, html)
        assertTrue(block.contains(oratoryFocusBlock(title, html, title)))
    }
}
