package com.bettertalker.app.ui.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T2 (polimento visual) — parser/inline: lista ordenada preserva número,
 * `código` vira monoespaçado e os títulos têm níveis distintos.
 */
class MarkdownBlocksTest {

    @Test
    fun ordenadaPreservaNumero() {
        val blocks = parseBlocks("1. primeiro\n2. segundo\n10. décimo")
        assertEquals(3, blocks.size)
        val b = blocks.map { it as MdBlock.Bullet }
        assertEquals(listOf(1, 2, 10), b.map { it.number })
        assertEquals(listOf("primeiro", "segundo", "décimo"), b.map { it.text })
    }

    @Test
    fun bulletComumSegueSemNumero() {
        val b = parseBlocks("- item\n* outro").map { it as MdBlock.Bullet }
        assertEquals(listOf(null, null), b.map { it.number })
    }

    @Test
    fun titulosTemNiveisDistintos() {
        val t = parseBlocks("# Um\n## Dois\n### Três").map { it as MdBlock.Title }
        assertEquals(listOf(1, 2, 3), t.map { it.level })
        assertEquals(listOf("Um", "Dois", "Três"), t.map { it.text })
    }

    @Test
    fun backtickViraMonospace() {
        val a = buildInline("use `codigo` aqui")
        val mono = a.spanStyles.firstOrNull { it.item.fontFamily == FontFamily.Monospace }
        assertNotNull("esperava span mono", mono)
        assertEquals("codigo", a.text.substring(mono!!.start, mono.end))
    }

    @Test
    fun enfaseContinuaFuncionando() {
        val bold = buildInline("**forte**").spanStyles
            .firstOrNull { it.item.fontWeight == FontWeight.Bold }
        assertNotNull("esperava negrito", bold)
        val ital = buildInline("*leve*").spanStyles
            .firstOrNull { it.item.fontStyle == FontStyle.Italic }
        assertNotNull("esperava itálico", ital)
    }
}
