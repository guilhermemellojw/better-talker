package com.bettertalker.app.data.publication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationReferenceLinksTest {

    private fun spans(text: String) = PublicationReferenceSpanDetector.detect(text)

    @Test
    fun detectaRevistaComPaginaEParagrafos() {
        val text = "Veja w25.07 22 §§ 8-9."
        val occ = spans(text).single()
        assertEquals("w25.07 22 §§ 8-9", occ.raw)
        assertEquals("w", occ.publicationKey)
        assertEquals("w|2025|7", occ.editionKey)
        assertEquals(22, occ.page)
        assertEquals(8, occ.paragraph)
        assertEquals(9, occ.paragraphEnd)
        assertEquals(text.indexOf("w25.07"), occ.start)
        assertEquals(occ.start + occ.raw.length, occ.endExclusive)
        assertEquals(occ.raw, text.substring(occ.start, occ.endExclusive))
    }

    @Test
    fun detectaLicaoComPonto() {
        val occ = spans("Use lmd lição 3 ponto 4.").single()
        assertEquals("lmd lição 3 ponto 4", occ.raw)
        assertEquals("lmd", occ.publicationKey)
        assertEquals("lição 3", occ.chapter)
        assertEquals(4, occ.paragraph)
    }

    @Test
    fun detectaPaginaDeLivro() {
        val occ = spans("Confira jr 29.").single()
        assertEquals("jr 29", occ.raw)
        assertEquals("jr", occ.publicationKey)
        assertEquals(29, occ.page)
    }

    @Test
    fun detectaSimboloIsoladoInequívoco() {
        val occ = spans("Use it-1 como fonte.").single()
        assertEquals("it-1", occ.raw)
        assertEquals("it-1", occ.publicationKey)
        assertEquals("book|it-1", occ.editionKey)
    }

    @Test
    fun detectaTituloComUnidade() {
        val occ = spans("Veja lff lição 12.").single()
        assertEquals("lff lição 12", occ.raw)
        assertEquals("lff", occ.publicationKey)
        assertEquals("lição 12", occ.chapter)
    }

    @Test
    fun duasReferenciasDistintasMantemOrdem() {
        val text = "Primeiro w25.07 22 § 8; depois lmd lição 3."
        val occs = spans(text)
        assertEquals(2, occs.size)
        assertEquals(listOf("w25.07 22 § 8", "lmd lição 3"), occs.map { it.raw })
        assertTrue(occs[0].start < occs[1].start)
    }

    @Test
    fun referenciaRepetidaGeraDuasOcorrencias() {
        val text = "w25.07 22 § 8. Repita w25.07 22 § 8."
        val occs = spans(text)
        assertEquals(2, occs.size)
        assertEquals(listOf("w25.07 22 § 8", "w25.07 22 § 8"), occs.map { it.raw })
        assertTrue(occs[0].start < occs[1].start)
    }

    @Test
    fun tituloSemelhanteNaoViraOutraPublicacao() {
        val occ = spans("Veja lffi lição 3.").single()
        assertEquals("lffi", occ.publicationKey)
        assertFalse(occ.publicationKey == "lff")
    }

    @Test
    fun numeroIsoladoNaoEhPublicacao() {
        assertTrue(spans("O ponto 29 foi citado.").isEmpty())
        assertTrue(spans("29").isEmpty())
    }

    @Test
    fun ignoraCodigoInline() {
        assertTrue(spans("Use `w25.07 22 § 8` no código.").isEmpty())
    }

    @Test
    fun ignoraCodeFence() {
        val text = "```\nw25.07 22 § 8\n```"
        assertTrue(spans(text).isEmpty())
    }

    @Test
    fun ignoraUrlEQuandoElaContemReferencia() {
        assertTrue(spans("https://jw.org/w25.07 22 § 8").isEmpty())
    }

    @Test
    fun ignoraLinkMarkdownInteiro() {
        assertTrue(spans("[w25.07 22 § 8](https://jw.org)").isEmpty())
    }

    @Test
    fun ignoraLinkMarkdownPorReferencia() {
        assertTrue(spans("[Veja w25.07 22 § 8][ref]").isEmpty())
    }

    @Test
    fun ignoraDefinicaoDeReferenciaMarkdown() {
        assertTrue(spans("[ref]: https://jw.org/w25.07 22 § 8").isEmpty())
    }

    @Test
    fun ignoraLinkMarkdownComColchetesAninhados() {
        assertTrue(spans("[Veja [w25.07 22 § 8] aqui](https://jw.org)").isEmpty())
    }

    @Test
    fun ignoraCodeFenceComTil() {
        assertTrue(spans("~~~\nw25.07 22 § 8\n~~~").isEmpty())
    }

    @Test
    fun ignoraCodeFenceComCrasesExtensas() {
        assertTrue(spans("````\nw25.07 22 § 8\n````").isEmpty())
    }

    @Test
    fun ignoraCodigoInlineComCrasesDuplas() {
        assertTrue(spans("Use ``w25.07 22 § 8`` no código.").isEmpty())
    }

    @Test
    fun referenciaForaDeMarkdownProtegidoContinuaDetectada() {
        val text = "[ref]: https://jw.org\nVeja w25.07 22 § 8."
        assertEquals("w25.07 22 § 8", spans(text).single().raw)
    }

    @Test
    fun textoBiblicoNaoViraPublicacao() {
        assertTrue(spans("Leia João 3:16 e Je 29.").isEmpty())
        assertTrue(spans("Re 1:1").isEmpty())
    }

    @Test
    fun spansNaoSeSobrepoemEPreservamTexto() {
        val text = "it-1 813 e it-2 10 e lmd lição 2."
        val occs = spans(text)
        assertTrue(occs.isNotEmpty())
        occs.forEach { occ ->
            assertEquals(occ.raw, text.substring(occ.start, occ.endExclusive))
        }
        occs.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endExclusive <= b.start)
        }
    }

    @Test
    fun naoAtivaTituloGenericoSemUnidade() {
        assertTrue(spans("O livro Seja Feliz para Sempre é bom.").isEmpty())
    }

    @Test
    fun tituloSoViraLinkNaOcorrenciaComUnidadeExplicita() {
        val text = "O livro Achegue-se a Jeová é bom. " +
            "Veja Achegue-se a Jeová, capítulo 5."
        val occ = spans(text).single()
        assertEquals("Achegue-se a Jeová", occ.raw)
        assertEquals(text.lastIndexOf("Achegue-se a Jeová"), occ.start)
        // O detector normaliza a unidade do título ("capítulo" → "capitulo").
        assertEquals("capitulo 5", occ.chapter)
    }

    @Test
    fun tituloComPaginasPluralCapturaUnidade() {
        val occ = spans("Veja Achegue-se a Jeová, páginas 52-55.").single()
        assertEquals("Achegue-se a Jeová", occ.raw)
        assertEquals(52, occ.page)
        assertEquals(55, occ.pageEnd)
    }

    @Test
    fun tituloCompartilhadoPorDuasPublicacoesNaoViraLink() {
        // "Seja Feliz para Sempre!" é título de lff e lffi.
        assertTrue(spans("Veja Seja Feliz para Sempre, lição 5.").isEmpty())
    }

    @Test
    fun urlSemEsquemaNaoViraLink() {
        assertTrue(spans("Visite jw.org/lmd para saber mais.").isEmpty())
    }

    @Test
    fun textoBiblicoComCapítuloNaoViraPublicacao() {
        assertTrue(spans("Jeremias 52 e Salmo 23 são capítulos bíblicos.").isEmpty())
    }
}
