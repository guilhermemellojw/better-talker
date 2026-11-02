package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsGroupingTest {
    private fun cand(name: String, size: Long = 100) =
        DownloadCandidate("uri://$name", name, size)

    private fun att(fileName: String, symbol: String? = null) = AttachmentEntity(
        id = "a-$fileName",
        noteId = null,
        fileName = fileName,
        kind = fileName.substringAfterLast('.', "bin"),
        sizeBytes = 100,
        appPath = "/tmp/$fileName",
        indexed = true,
        addedAt = 0,
        symbol = symbol,
    )

    @Test
    fun mesmaPublicacaoPrefereJwpubEDuplicata() {
        val groups = groupDownloadCandidates(
            listOf(
                cand("w25.07.pdf"),
                cand("w25.07.jwpub"),
                cand("w25.07.epub"),
            ),
            registeredNames = emptySet(),
            library = emptyList(),
        )
        assertEquals(1, groups.size)
        assertEquals("w25.07", groups.single().symbol)
        assertEquals("w25.07.jwpub", groups.single().best.name)
        assertEquals(2, groups.single().duplicateCount)
        assertFalse(groups.single().alreadyInLibrary)
    }

    @Test
    fun jaNoAcervoPorSimboloOuNome() {
        val bySymbol = groupDownloadCandidates(
            listOf(cand("be_T.pdf")),
            registeredNames = emptySet(),
            library = listOf(att("outro.pdf", symbol = "be")),
        )
        assertTrue(bySymbol.single().alreadyInLibrary)

        val byName = groupDownloadCandidates(
            listOf(cand("lmd_T.jwpub")),
            registeredNames = setOf("lmd_T.jwpub"),
            library = emptyList(),
        )
        assertTrue(byName.single().alreadyInLibrary)
    }

    @Test
    fun formatRankJwpubMaiorQueEpubQuePdf() {
        assertTrue(downloadFormatRank("x.jwpub") > downloadFormatRank("x.epub"))
        assertTrue(downloadFormatRank("x.epub") > downloadFormatRank("x.pdf"))
    }
}
