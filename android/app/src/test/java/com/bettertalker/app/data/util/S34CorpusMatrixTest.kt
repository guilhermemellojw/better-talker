package com.bettertalker.app.data.util

import com.bettertalker.app.domain.speech.OutlineConverter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regressão de corpus C2.6/C2.7 sobre os S-34 reais em ~/Downloads.
 * Só roda quando a pasta existe (CI não tem os DOCX); é a matriz de detecção
 * e conversão, sem depender do acervo de publicações.
 */
class S34CorpusMatrixTest {

    private val dir = File("/home/guilherme/Downloads")
    private val regex = Regex(
        """S-34_T_(00[1-9]|01[0-9]|020|0[7-9][0-9]|10[0-9])\.docx""",
    )

    private fun files(): List<File> {
        assumeTrue("DOCX S-34 ausentes em $dir", dir.isDirectory)
        val list = dir.listFiles { f -> f.name.matches(regex) }?.sortedBy { it.name }.orEmpty()
        assumeTrue("DOCX S-34 ausentes em $dir", list.isNotEmpty())
        return list
    }

    /** Cauda imediata da ref até o próximo separador forte. */
    private fun tail(line: String, raw: String): String {
        val idx = line.indexOf(raw)
        if (idx < 0) return ""
        return line.substring(idx + raw.length)
            .take(50)
            .substringBefore(';')
            .substringBefore(')')
            .substringBefore(']')
            .lowercase()
    }

    @Test
    fun matriz49Esbocos_semPerderPaginaOuParagrafo() {
        var refs = 0
        var lostPage = 0
        var lostParagraph = 0
        val lostSamples = mutableListOf<String>()

        for (file in files()) {
            val text = DocExtractors.readDocx(file)
            for (line in text.lines()) {
                for (ref in RefDetector.detect(line)) {
                    refs++
                    val t = tail(line, ref.raw)
                    if ((t.contains("pág") || t.contains("pag.")) && ref.page == null) {
                        lostPage++
                        lostSamples += "${file.name}: ${ref.raw} → $t"
                    }
                    if ((t.contains("§") || t.contains("parág") || t.contains("parag.")) &&
                        ref.paragraph == null
                    ) {
                        lostParagraph++
                        lostSamples += "${file.name}: ${ref.raw} → $t"
                    }
                }
            }
        }

        println("C2_MATRIX files=${files().size} refs=$refs lostPage=$lostPage lostParagraph=$lostParagraph")
        lostSamples.take(20).forEach { println("C2_LOST $it") }
        assertEquals("refs detectadas", 391, refs)
        assertEquals("página perdida", 0, lostPage)
        assertEquals("parágrafo perdido", 0, lostParagraph)
    }

    @Test
    fun parsersDos49Esbocos_reconhecemEstrutura() {
        var parsedByOutlineParser = 0
        var parsedByS34 = 0
        for (file in files()) {
            val text = DocExtractors.readDocx(file)
            val parsed = OutlineParser.parse(text, file.name)
            if (parsed.sections.isEmpty()) {
                if (com.bettertalker.app.data.s34.S34Parser.parseS34(text).sections.isNotEmpty()) {
                    parsedByS34++
                }
                continue
            }
            parsedByOutlineParser++
            OutlineConverter().convert(parsed, "matrix-${file.name}")
        }
        println("C2_STRUCTURE files=${files().size} " +
            "outlineParser=$parsedByOutlineParser s34Parser=$parsedByS34")
        assertEquals(
            files().size,
            parsedByOutlineParser + parsedByS34,
        )
    }

    @Test
    fun casosC27LivroTituloEDedupLvs() {
        val all = files().associate { it.name to DocExtractors.readDocx(it) }
        val t009 = all["S-34_T_009.docx"].orEmpty()
        val be = RefDetector.detect(t009).filter { it.editionKey == "book|be" }
        assertTrue("Beneficie-se sem página: $be", be.any { it.page == 52 && it.pageEnd == 55 })

        val lvs194 = all.values.flatMap { text ->
            RefDetector.detect(text).filter { it.editionKey == "book|lvs" && it.page == 194 }
        }
        assertTrue("lvs 194 ausente", lvs194.isNotEmpty())
        assertEquals(
            "lvs 194 duplicado: $lvs194",
            1,
            lvs194.count { it.paragraph == 18 },
        )
    }
}
