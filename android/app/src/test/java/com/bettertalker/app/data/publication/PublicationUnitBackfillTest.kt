package com.bettertalker.app.data.publication

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.PublicationUnitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationUnitBackfillTest {

    private fun attachment(fileName: String = "wp_T_201709.jwpub") = AttachmentEntity(
        id = "att",
        noteId = null,
        fileName = fileName,
        kind = if (fileName.endsWith(".jwpub")) "jwpub" else "epub",
        sizeBytes = 1,
        appPath = "/tmp/$fileName",
        indexed = true,
        addedAt = 0,
    )

    private fun passage(ord: Int, section: String) = PassageEntity(
        id = "att-p$ord",
        attachmentId = "att",
        text = "texto $ord",
        normalized = "texto $ord",
        section = section,
        ord = ord,
    )

    private fun existingUnit() = PublicationUnitEntity(
        id = "att-u0",
        attachmentId = "att",
        kind = "DOCUMENT",
        title = "Documento",
        canonicalKey = "wp17.09:document:documento",
        ord = 0,
        startOrd = 0,
        endOrd = 1,
    )

    @Test
    fun backfillReusaIndiceExistente() {
        val existing = listOf(existingUnit())
        val result = PublicationUnitBackfill.buildIfMissing(
            attachment = attachment(),
            passages = listOf(passage(0, "Artigo 1")),
            existing = existing,
        )
        assertSame(existing, result)
    }

    @Test
    fun backfillMontaUnidadesDasPassagesExistentes() {
        val result = PublicationUnitBackfill.buildIfMissing(
            attachment = attachment(),
            passages = listOf(
                passage(0, "Primeiro artigo"),
                passage(1, "Primeiro artigo"),
                passage(2, "Segundo artigo"),
            ),
        )
        assertEquals(listOf("ARTICLE", "ARTICLE"), result.map { it.kind })
        assertEquals(listOf(0, 2), result.map { it.startOrd })
        assertEquals(listOf(1, 2), result.map { it.endOrd })
    }

    @Test
    fun backfillDeEpubProduzDocumento() {
        val result = PublicationUnitBackfill.buildIfMissing(
            attachment = attachment("livro.epub"),
            passages = listOf(
                passage(0, "Capítulo 1"),
                passage(1, "Capítulo 2"),
            ),
        )
        assertEquals(1, result.size)
        assertEquals("DOCUMENT", result.single().kind)
        assertEquals(0, result.single().startOrd)
        assertEquals(1, result.single().endOrd)
    }

    @Test
    fun backfillSemPassagesNaoCriaNada() {
        assertTrue(
            PublicationUnitBackfill.buildIfMissing(
                attachment = attachment(),
                passages = emptyList(),
            ).isEmpty()
        )
    }
}
