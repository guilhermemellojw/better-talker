package com.bettertalker.app.ui.components

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    // ---------- T3: links ----------

    @Test
    fun linkJwViraAnotacaoClicavel() {
        val a = buildInline("Veja [a página](https://wol.jw.org/pt/wol/d/x) agora")
        val links = a.getLinkAnnotations(0, a.length)
        assertEquals(1, links.size)
        val url = (links.first().item as LinkAnnotation.Url).url
        assertEquals("https://wol.jw.org/pt/wol/d/x", url)
        assertTrue(a.text.contains("a página"))
        assertFalse(a.text.contains("wol.jw.org"))
    }

    @Test
    fun linkDeOutroDominioNaoEhClicavel() {
        val a = buildInline("[exemplo](https://exemplo.com/x)")
        assertEquals(0, a.getLinkAnnotations(0, a.length).size)
        assertEquals("exemplo", a.text)
        // Continua sublinhado (era um link), mas inerte.
        assertTrue(a.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun isJwUrlPuro() {
        assertTrue(isJwUrl("https://jw.org/pt"))
        assertTrue(isJwUrl("https://www.jw.org/finder"))
        assertTrue(isJwUrl("https://wol.jw.org/pt/wol/d/x"))
        assertFalse(isJwUrl("https://exemplo.com/jw.org"))
        assertFalse(isJwUrl("https://jw.org.evil.com/x"))
        assertFalse(isJwUrl("http://notjw.org"))
    }
}
