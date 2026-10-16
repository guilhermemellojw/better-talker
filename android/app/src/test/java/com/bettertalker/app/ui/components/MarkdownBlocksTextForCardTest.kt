package com.bettertalker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #21-fix — <texto-do-card> vira bloco próprio na posição do texto. Puro. */
class MarkdownBlocksTextForCardTest {

    @Test
    fun tagViraBlocoUnico() {
        val blocks = parseBlocks("<texto-do-card>abc</texto-do-card>")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.TextForCard("abc"), blocks[0])
    }

    @Test
    fun semTagNaoTemBloco() {
        val blocks = parseBlocks("Só conversa, sem bloco.")
        assertTrue(blocks.none { it is MdBlock.TextForCard })
    }

    @Test
    fun conteudoInternoPreservadoNoParse() {
        val blocks = parseBlocks("<texto-do-card>〈sugestão〉abc〈/sugestão〉</texto-do-card>")
        assertEquals(1, blocks.size)
        assertEquals("〈sugestão〉abc〈/sugestão〉", (blocks[0] as MdBlock.TextForCard).text)
    }

    @Test
    fun multiplosBlocosEmOrdem() {
        val blocks = parseBlocks(
            "<texto-do-card>um</texto-do-card>\n\ntexto solto\n\n<texto-do-card>dois</texto-do-card>"
        )
        val cards = blocks.filterIsInstance<MdBlock.TextForCard>().map { it.text }
        assertEquals(listOf("um", "dois"), cards)
    }
}
