package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #26-fix: prévia limpa da Home (HTML + Markdown + marcadores → texto puro). */
class TextSanitizerTest {

    @Test
    fun tagsHtmlSomem() {
        assertEquals("Olá mundo", "<p>Olá <b>mundo</b></p>".toCleanPreview())
    }

    @Test
    fun negritoMarkdownSome() {
        assertEquals("1. Item", "**1.** Item".toCleanPreview())
    }

    @Test
    fun marcadorDeSugestaoSome() {
        assertEquals("ideia", "〈sugestão〉ideia〈/sugestão〉".toCleanPreview())
    }

    @Test
    fun entidadesHtmlDecodificam() {
        assertEquals("começar com atenção", "come&ccedil;ar com aten&ccedil;&atilde;o".toCleanPreview())
        assertEquals("Já? Sim.", "J&aacute;&quest; Sim&period;".toCleanPreview())
        assertEquals("a b", "a&nbsp;b".toCleanPreview())
        assertEquals("é", "&#233;".toCleanPreview())
        // #E2E-fix: pontuação que o editor rich gera em entidades.
        assertEquals("… (a) 50%", "&mldr; &lpar;a&rpar; 50&percnt;".toCleanPreview())
        // Aspas curvas do editor (apareciam como "ldquo" no Ensaio).
        assertEquals("“caminho” ‘vida’", "&ldquo;caminho&rdquo; &lsquo;vida&rsquo;".toCleanPreview())
        // Duplo escape do editor: &amp;ldquo; → &ldquo; → “.
        assertEquals("“caminho”", "&amp;ldquo;caminho&amp;rdquo;".toCleanPreview())
    }

    @Test
    fun casoRealDaHomeLimpa() {
        // Payload real do card "VOCÊ ESTÁ AVANÇANDO" (backup do A34).
        val raw = "<br><br>\n<p><span style=\"color: rgba(245, 124, 0, 1.0); " +
            "font-size: 24.0px;\"><b>Vamos</b></span> come&ccedil;ar com uma pergunta simples"
        val clean = raw.toCleanPreview()
        assertTrue(clean, clean.startsWith("Vamos começar com uma pergunta simples"))
        assertFalse(clean, clean.contains("<"))
        assertFalse(clean, clean.contains("&"))
    }

    @Test
    fun truncamentoComReticencias() {
        val long = "palavra ".repeat(40)
        val out = long.toCleanPreview(maxLength = 20)
        assertTrue(out, out.length <= 21)
        assertTrue(out, out.endsWith("…"))
    }

    @Test
    fun marcadoresDoCopilotSomem() {
        val raw = "<texto-do-card>Fale disso.</texto-do-card>〈decisão〉usar o gancho〈/decisão〉"
        val clean = raw.toCleanPreview()
        // As tags saem com espaço (evita grudar palavras); os marcadores 〈〉 saem limpos.
        assertEquals("Fale disso. usar o gancho", clean)
        assertFalse(clean, clean.contains("〈"))
    }
}
