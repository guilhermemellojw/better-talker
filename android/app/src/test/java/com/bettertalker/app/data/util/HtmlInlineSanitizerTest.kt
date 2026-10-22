package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exp/chat-contrast (2026-10-22) — HTML inline escuro (cor do S-34 colado)
 * e HTML cru no chat.
 *
 * Evidência real (A34, `speech_sections.contentHtml`):
 * `<span style="color: rgba(123, 31, 162, 1.0); font-size: 18.0px;">` — roxo
 * sobre a surface escura = **2,13:1** (invisível).
 */
class HtmlInlineSanitizerTest {

    /** HTML real do S-34 (nota antiga, `.docx` do S-34). */
    private val htmlS34 =
        "<p><span style=\"color: rgba(123, 31, 162, 1.0); font-size: 18.0px;\">Voc&ecirc;</span> " +
            "j&aacute; se perguntou como Jeov&aacute; nos protege individualmente&quest;</p>"

    private val onSurfaceClaro = 0xFF1B1B1B   // preto de tema (light)
    private val onSurfaceEscuro = 0xFFD7E6EF  // claro de tema (dark, Oceano)

    @Test
    fun sanitizeRichHtml_normalizaCorEscuraNoDark() {
        val out = HtmlInlineSanitizer.sanitizeRichHtmlForTheme(
            html = htmlS34,
            dark = true,
            fallbackArgb = onSurfaceEscuro,
        )
        // o roxo (escuro) sai; a cor do tema entra
        assertFalse("cor escura do S-34 deve sair no dark", out.contains("123, 31, 162"))
        assertTrue("a cor do tema deve entrar", out.contains("215, 230, 239"))
        // o TEXTO é intocado
        assertTrue(out.contains("Voc"))
        assertTrue(out.contains("j&aacute; se perguntou"))
    }

    @Test
    fun sanitizeRichHtml_preservaCorClara() {
        // laranja do S-34 (clara) sobrevive no dark…
        val clara = "<span style=\"color: rgba(245, 124, 0, 1.0);\">Vamos</span>"
        val out = HtmlInlineSanitizer.sanitizeRichHtmlForTheme(clara, dark = true, fallbackArgb = onSurfaceEscuro)
        assertTrue("cor clara deve sobreviver", out.contains("245, 124, 0"))
    }

    @Test
    fun sanitizeRichHtml_noLightNaoMexeEmNada() {
        val out = HtmlInlineSanitizer.sanitizeRichHtmlForTheme(
            html = htmlS34,
            dark = false,
            fallbackArgb = onSurfaceClaro,
        )
        assertEquals("no light o HTML fica intacto", htmlS34, out)
    }

    @Test
    fun htmlInlineToMarkdown_someComTagEComCor() {
        val cru = "Antes <span style=\"color: rgba(123, 31, 162, 1.0);\">assim</span> depois"
        val out = HtmlInlineSanitizer.htmlInlineToMarkdown(cru)
        assertFalse("a tag com cor não pode sobrar crua", out.contains("<span"))
        assertFalse("a cor não pode sobrar", out.contains("123, 31, 162"))
        assertTrue(out.contains("assim"))
    }

    @Test
    fun htmlInlineToMarkdown_converteNegritoEEntidades() {
        val cru = "<p>Voc&ecirc; <b>se perguntou</b> como Jeov&aacute;?</p>"
        val out = HtmlInlineSanitizer.htmlInlineToMarkdown(cru)
        assertTrue("negrito vira markdown", out.contains("**se perguntou**"))
        assertTrue("entidade acentuada é decodificada", out.contains("Você"))
        assertTrue("entidade acentuada 2 é decodificada", out.contains("Jeová"))
        assertFalse(out.contains("&ecirc;"))
    }
}
