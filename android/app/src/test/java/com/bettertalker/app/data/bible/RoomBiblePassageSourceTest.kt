package com.bettertalker.app.data.bible

import com.bettertalker.app.data.db.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.2 — seleção da fonte: TNM base primeiro; fallback Bíblia indexada. */
class RoomBiblePassageSourceTest {

    private fun attachment(
        id: String,
        name: String,
        baseSlot: String? = null,
        sourceType: String = "content",
        indexed: Boolean = true,
    ) = AttachmentEntity(
        id = id,
        noteId = null,
        fileName = name,
        kind = "epub",
        sizeBytes = 1,
        appPath = "/tmp/$name",
        indexed = indexed,
        addedAt = 1,
        baseSlot = baseSlot,
        sourceType = sourceType,
    )

    @Test
    fun tnmBaseTemPreferencia() {
        val tnm = attachment("nwt", "nwt_T.epub", baseSlot = "nwt", sourceType = "bible")
        val other = attachment("other", "Bíblia alternativa.epub", sourceType = "bible")
        val source = selectBibleSource(tnm, listOf(other, tnm))!!
        assertEquals("nwt", source.attachmentId)
        assertTrue(source.isTnm)
        assertEquals("TNM", source.label)
    }

    @Test
    fun semTnmUsaOutraBibliaIndexada() {
        val other = attachment("other", "Bíblia alternativa.epub", sourceType = "bible")
        val source = selectBibleSource(null, listOf(other))!!
        assertEquals("other", source.attachmentId)
        assertFalse(source.isTnm)
        assertEquals("Bíblia alternativa", source.label)
    }

    @Test
    fun semBibliaNaoInventaFonte() {
        assertNull(
            selectBibleSource(
                null,
                listOf(attachment("pub", "w25.07.epub", sourceType = "content")),
            )
        )
    }
}
