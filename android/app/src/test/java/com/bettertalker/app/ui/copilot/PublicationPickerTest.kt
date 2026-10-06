package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.db.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** T2 — sheet "Anexar do acervo": filtro e rótulos de status (puros). */
class PublicationPickerTest {

    private fun att(name: String, symbol: String? = null, status: String = "ready") =
        AttachmentEntity(
            id = name, noteId = null, fileName = name, kind = "pdf",
            sizeBytes = 1L, appPath = "", indexed = true, addedAt = 0L,
            status = status, symbol = symbol
        )

    @Test
    fun filtroPorNomeDeArquivo() {
        val items = listOf(att("be_T.pdf"), att("rr_T.pdf"))
        assertEquals(listOf("be_T.pdf"), filterPublications("be", items).map { it.fileName })
    }

    @Test
    fun filtroPorSimboloEQueryVazia() {
        val items = listOf(att("publicacao.pdf", symbol = "w19.03"), att("outra.pdf"))
        assertEquals(
            listOf("publicacao.pdf"),
            filterPublications("w19", items).map { it.fileName }
        )
        assertEquals(items, filterPublications("  ", items))
    }

    @Test
    fun rotulosDeStatus() {
        assertEquals("Indexado", publicationStatusLabel("ready"))
        assertEquals("Indexando…", publicationStatusLabel("indexing"))
        assertEquals("Falhou", publicationStatusLabel("failed"))
        assertEquals("Baixando…", publicationStatusLabel("downloading"))
    }
}
