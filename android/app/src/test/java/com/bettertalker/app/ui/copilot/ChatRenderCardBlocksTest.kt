package com.bettertalker.app.ui.copilot

import com.bettertalker.app.ui.components.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #20-fix-a — o preview do card parseia markdown (títulos/listas/○); o
 * INSERT continua passando o texto CRU. Puro.
 */
class ChatRenderCardBlocksTest {

    @Test
    fun cardComTituloEItemViraBlocosFormatados() {
        val inner = cardInnerBlocks("## Título\n- item")
        assertEquals(2, inner.size)
        assertEquals(MdBlock.Title(2, "Título"), inner[0])
        assertEquals(MdBlock.Bullet("item", null, 0), inner[1])
    }

    @Test
    fun cardComSubListaPreservaDepth() {
        val inner = cardInnerBlocks("- topo\n  - sub")
        assertEquals(MdBlock.Bullet("topo", null, 0), inner[0])
        assertEquals(MdBlock.Bullet("sub", null, 1), inner[1])
    }

    @Test
    fun cardAninhadoEhIgnorado() {
        val inner = cardInnerBlocks("<texto-do-card>x</texto-do-card>")
        assertTrue(inner.isEmpty())
    }

    @Test
    fun cardHtmlViraMarkdownComPapeisEmNegrito() {
        // #E2E-fix: HTML do Copilot → markdown para o insert preservar o
        // negrito dos papéis (senão o Ensaio não reconhece os turnos).
        val md = cardTextToMarkdown(
            "<p><b>Publicador:</b> Ol&aacute;, boa tarde&excl;</p>" +
                "<p><b>Morador:</b> Bom dia&period;</p>"
        )
        assertEquals("**Publicador:** Olá, boa tarde!\n\n**Morador:** Bom dia.", md)
    }

    @Test
    fun cardMarkdownPassaIntacto() {
        assertEquals("**Publicador:** oi", cardTextToMarkdown("**Publicador:** oi"))
    }
}
