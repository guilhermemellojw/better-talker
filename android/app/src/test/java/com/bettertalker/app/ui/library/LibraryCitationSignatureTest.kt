package com.bettertalker.app.ui.library

import com.bettertalker.app.data.db.AttachmentEntity
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A8: mudanças no acervo precisam invalidar a lista de Citadas. */
class LibraryCitationSignatureTest {
    private fun att(status: String, indexed: Boolean, baseSlot: String? = null) = AttachmentEntity(
        id = "a1",
        noteId = null,
        fileName = "w_T_202507.jwpub",
        kind = "jwpub",
        sizeBytes = 1L,
        appPath = "",
        indexed = indexed,
        addedAt = 0L,
        baseSlot = baseSlot,
        status = status,
    )

    @Test
    fun statusOuIndexacaoMudamAAssinatura() {
        val baixando = citationLibrarySignature(listOf(att("downloading", false)))
        val indexando = citationLibrarySignature(listOf(att("indexing", false)))
        val pronto = citationLibrarySignature(listOf(att("ready", true)))
        assertNotEquals(baixando, indexando)
        assertNotEquals(indexando, pronto)
    }

    @Test
    fun mesmoAcervoMantemAAssinatura() {
        val a = citationLibrarySignature(listOf(att("ready", true)))
        val b = citationLibrarySignature(listOf(att("ready", true)))
        assertEquals(a, b)
    }
}
