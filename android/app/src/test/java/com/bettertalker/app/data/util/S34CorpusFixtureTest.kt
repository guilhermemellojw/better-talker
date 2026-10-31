package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Fixture CI-safe da matriz C2.6/C2.7: cada linha traz a frase-contexto do
 * S-34 + ref/edição/unidade esperadas. Não depende dos DOCX originais.
 */
class S34CorpusFixtureTest {

    private data class Row(
        val file: String,
        val raw: String,
        val edition: String,
        val page: Int?,
        val pageEnd: Int?,
        val paragraph: Int?,
        val paragraphEnd: Int?,
        val context: String,
    )

    private fun rows(): List<Row> {
        val stream = javaClass.classLoader!!.getResourceAsStream("s34-c26-fixture.tsv")
        assertNotNull("fixture s34-c26-fixture.tsv ausente", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).readLines()
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val p = line.split('\t', limit = 8)
                if (p.size < 8) return@mapNotNull null
                Row(
                    file = p[0],
                    raw = p[1],
                    edition = p[2],
                    page = p[3].toIntOrNull(),
                    pageEnd = p[4].toIntOrNull(),
                    paragraph = p[5].toIntOrNull(),
                    paragraphEnd = p[6].toIntOrNull(),
                    context = p[7],
                )
            }
    }

    @Test
    fun fixtureCobre391RefsEreproduzUnidade() {
        val rows = rows()
        assertEquals("linhas do fixture", 391, rows.size)
        var checked = 0
        for (row in rows) {
            val refs = RefDetector.detect(row.context)
            val hit = refs.firstOrNull {
                it.raw == row.raw &&
                    it.editionKey == row.edition &&
                    it.page == row.page &&
                    it.pageEnd == row.pageEnd &&
                    it.paragraph == row.paragraph &&
                    it.paragraphEnd == row.paragraphEnd
            }
            assertNotNull(
                "${row.file}: ${row.raw} [${row.edition} p=${row.page}-${row.pageEnd} " +
                    "g=${row.paragraph}-${row.paragraphEnd}] em '${row.context}'\n" +
                    "detectado=${refs.joinToString { "${it.raw}:${it.editionKey}:p=${it.page}:g=${it.paragraph}" }}",
                hit,
            )
            checked++
        }
        assertEquals(391, checked)
    }
}
