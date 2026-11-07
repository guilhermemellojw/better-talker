package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card-first: o texto de `<texto-do-card>` é persistido em `contentHtml`.
 * O insert precisa converter markdown/HTML para o rich editor — antes o
 * texto era escapado dentro de `<p>` e os marcadores ficavam literais.
 */
class CardTextToEditorHtmlTest {

    @Test
    fun htmlDoCardViraHtmlDoEditorSemEscaparTags() {
        val html = cardTextToEditorHtml(
            "<p><b>Publicador:</b> Boa tarde!</p>" +
                "<p><b>Morador:</b> Bom dia.</p>"
        )
        assertTrue("negrito preservado: $html", Regex("(?i)<(b|strong)>Publicador").containsMatchIn(html))
        assertTrue("morador presente: $html", html.contains("Morador"))
        assertFalse("tags não podem aparecer escapadas: $html", html.contains("&lt;"))
        assertFalse("não pode sobrar HTML cru dentro de <p>: $html", html.contains("<p>&lt;"))
    }

    @Test
    fun markdownViraFormatacaoRichText() {
        val html = cardTextToEditorHtml("**Publicador:** oi")
        assertTrue("negrito convertido: $html", Regex("(?i)<(b|strong)>Publicador").containsMatchIn(html))
        assertFalse("marcador markdown não pode sobrar: $html", html.contains("**"))
    }

    @Test
    fun marcadoresInternosSomem() {
        val html = cardTextToEditorHtml("Texto 〈sugestão〉sugerido〈/sugestão〉 fim.")
        assertTrue(html.contains("sugerido"))
        assertFalse("marcador não pode vazar: $html", html.contains("sugestão"))
    }

    @Test
    fun textoPuroViraParagrafos() {
        val html = cardTextToEditorHtml("Primeiro parágrafo.\n\nSegundo parágrafo.")
        assertTrue("html: $html", html.contains("Primeiro par"))
        assertTrue("html: $html", html.contains("Segundo par"))
        assertTrue("deve gerar blocos HTML: $html", html.contains("<p>"))
    }

    @Test
    fun textoVazioNaoGeraHtml() {
        assertTrue(cardTextToEditorHtml("").isEmpty())
        assertTrue(cardTextToEditorHtml("   ").isEmpty())
        assertTrue(cardTextToEditorHtml("〈sugestão〉〈/sugestão〉").isBlank())
    }
}
