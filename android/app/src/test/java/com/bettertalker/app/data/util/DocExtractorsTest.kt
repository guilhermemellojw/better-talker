package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 3.2.3a-fix4c: `readDocx` — correção do mapeamento de `<w:br>`/`<w:tab>`
 * (soft break/tab não podem virar linha, senão o body do esboço fragmenta
 * em sub-pontos — achado na validação ADB do S-34-T N.º 35: 89 → ~28).
 */
class DocExtractorsTest {

    private fun docx(bodyXml: String): File {
        val f = File.createTempFile("docx-test", ".docx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(bodyXml.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return f
    }

    private fun paragraph(vararg runs: String) = "<w:p>" + runs.joinToString("") + "</w:p>"

    private fun run(text: String) = "<w:r><w:t>$text</w:t></w:r>"

    @Test
    fun readDocx_softBreak_becomesSpace() {
        val f = docx(paragraph(run("texto"), "<w:br/>", run("continuação")))
        try {
            val out = DocExtractors.readDocx(f)
            assertTrue("esperado 'texto continuação', veio: $out", out.contains("texto continuação"))
            assertFalse("soft break não pode virar linha", out.contains("texto\ncontinuação"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun readDocx_paragraphBoundary_becomesDoubleNewline() {
        val f = docx(paragraph(run("A")) + paragraph(run("B")))
        try {
            val out = DocExtractors.readDocx(f)
            // espaços de tags podem sobrar nas bordas; o contrato é o \n\n entre parágrafos
            assertTrue(
                "esperado 'A\\n\\nB' (sem espaços), veio: ${out.replace("\n", "\\n")}",
                out.replace(" ", "").contains("A\n\nB")
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun readDocx_tab_becomesSpace() {
        val f = docx(paragraph(run("a"), "<w:tab/>", run("b")))
        try {
            val out = DocExtractors.readDocx(f)
            assertTrue("esperado 'a b', veio: $out", out.contains("a b"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun readDocx_s34WithSoftBreaks_doesNotFragmentBody() {
        // 3 "linhas" de body com soft breaks internos — não podem virar
        // linhas extras (é o cenário do docx real do S-34-T N.º 35).
        val f = docx(
            paragraph(run("Primeira"), "<w:br/>", run("linha"), "<w:br/>", run("inteira")) +
                paragraph(run("Segunda"), "<w:br/>", run("linha")) +
                paragraph(run("Terceira"), "<w:br/>", run("linha"), "<w:br/>", run("completa"))
        )
        try {
            val out = DocExtractors.readDocx(f)
            val linhas = out.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            assertEquals("devem sobrar 3 linhas (não fragmentar): $linhas", 3, linhas.size)
            assertEquals("Primeira linha inteira", linhas[0])
            assertEquals("Segunda linha", linhas[1])
            assertEquals("Terceira linha completa", linhas[2])
        } finally {
            f.delete()
        }
    }

    @Test
    fun readDocx_paragraphProperties_doNotFragmentLine() {
        // <w:pPr>/<w:pStyle>/<w:rPr> são PROPRIEDADES — não podem virar linha.
        // Era a causa real da fragmentação no docx do S-34-T real (o regex
        // antigo casava o prefixo "<w:p" de "<w:pPr>").
        val f = docx(
            "<w:p><w:pPr><w:pStyle w:val=\"Normal\"/><w:rPr><w:b/></w:rPr></w:pPr>" +
                "<w:r><w:t>linha íntegra do corpo</w:t></w:r></w:p>"
        )
        try {
            val out = DocExtractors.readDocx(f)
            val linhas = out.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            assertEquals("pPr não pode gerar linha: $linhas", 1, linhas.size)
            assertEquals("linha íntegra do corpo", linhas[0])
        } finally {
            f.delete()
        }
    }
}
