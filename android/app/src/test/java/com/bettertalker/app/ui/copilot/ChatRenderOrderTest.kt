package com.bettertalker.app.ui.copilot

import com.bettertalker.app.ui.components.MdBlock
import com.bettertalker.app.ui.components.parseBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #21-fix — ordem do render: parse no texto cru → strip por bloco. Puro. */
class ChatRenderOrderTest {

    @Test
    fun blocoFicaNaPosicaoDoTexto() {
        val blocks = parseBlocks("antes\n\n<texto-do-card>X</texto-do-card>\n\ndepois")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Para("antes"), blocks[0])
        assertEquals(MdBlock.TextForCard("X"), blocks[1])
        assertEquals(MdBlock.Para("depois"), blocks[2])
    }

    @Test
    fun cleanBlocksStripaDentroDoCard() {
        val blocks = cleanBlocks(
            parseBlocks("<texto-do-card>〈sugestão〉X〈/sugestão〉</texto-do-card>")
        )
        assertEquals(MdBlock.TextForCard("X"), blocks[0])
    }

    @Test
    fun cleanBlocksStripaNosDemaisBlocos() {
        val blocks = cleanBlocks(parseBlocks("〈decisão〉Y〈/decisão〉"))
        assertEquals(MdBlock.Para("Y"), blocks[0])
    }

    @Test
    fun badgeContinuaDetectado() {
        val s = stripSuggestionTags("Prosa. 〈sugestão〉criação〈/sugestão〉")
        assertTrue(s.hasSuggestion)
        assertEquals("Prosa. criação", s.text)
    }
}
