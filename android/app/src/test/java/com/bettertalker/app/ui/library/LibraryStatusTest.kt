package com.bettertalker.app.ui.library

import com.bettertalker.app.data.db.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStatusTest {
    private fun att(status: String) = AttachmentEntity(
        id = "a1",
        noteId = null,
        fileName = "arquivo.pdf",
        kind = "pdf",
        sizeBytes = 1L,
        appPath = "",
        indexed = status == "ready",
        addedAt = 0L,
        status = status,
    )

    @Test
    fun mapeiaEstadosDoAnexoParaUmaUnicaPilula() {
        assertEquals(LibraryStatus.READY, LibraryStatus.fromAttachment(att("ready")))
        assertEquals(LibraryStatus.DOWNLOADING, LibraryStatus.fromAttachment(att("downloading")))
        assertEquals(LibraryStatus.INDEXING, LibraryStatus.fromAttachment(att("indexing")))
        assertEquals(LibraryStatus.MISSING, LibraryStatus.fromAttachment(att("missing")))
        assertEquals(LibraryStatus.FAILED, LibraryStatus.fromAttachment(att("failed")))
        assertEquals("Disponível", LibraryStatus.READY.label)
        assertEquals("Falta o arquivo", LibraryStatus.MISSING.label)
    }
}
