package com.bettertalker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** #22-fix-b — hierarquia de listas: a indentação vira depth. Puro. */
class MarkdownBlocksDepthTest {

    @Test
    fun bulletNoTopoEhDepthZero() {
        assertEquals(MdBlock.Bullet("Item", null, 0), parseBlocks("- Item")[0])
    }

    @Test
    fun subComDoisEspacosEhDepthUm() {
        assertEquals(MdBlock.Bullet("Sub", null, 1), parseBlocks("  - Sub")[0])
    }

    @Test
    fun subsubComQuatroEspacosEhDepthDois() {
        assertEquals(MdBlock.Bullet("Subsub", null, 2), parseBlocks("    - Subsub")[0])
    }

    @Test
    fun tabNormalizaParaDepthUm() {
        assertEquals(MdBlock.Bullet("Tab", null, 1), parseBlocks("\t- Tab")[0])
    }

    @Test
    fun sequenciaMantemOsTresNiveis() {
        val blocks = parseBlocks("- A\n  - B\n    - C")
        assertEquals(
            listOf(
                MdBlock.Bullet("A", null, 0),
                MdBlock.Bullet("B", null, 1),
                MdBlock.Bullet("C", null, 2),
            ),
            blocks,
        )
    }

    @Test
    fun checklistIndentadoRespeitaDepth() {
        val blocks = parseBlocks("- [x] X\n  - [ ] Y")
        assertEquals(
            listOf(
                MdBlock.Check(true, "X", 0),
                MdBlock.Check(false, "Y", 1),
            ),
            blocks,
        )
    }

    @Test
    fun bulletComumSegueDepthZero() {
        // Regressão: sem indentação nada muda.
        assertEquals(MdBlock.Bullet("Sem número", null, 0), parseBlocks("- Sem número")[0])
    }
}
