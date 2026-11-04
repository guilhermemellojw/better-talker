package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A9: restaurar arquivo não pode trocar a publicação do anexo. */
class LibraryRepairValidationTest {

    private fun att(
        name: String,
        baseSlot: String? = null,
        symbol: String? = null,
    ) = AttachmentEntity(
        id = "a1",
        noteId = "n1",
        fileName = name,
        kind = name.substringAfterLast('.', "pdf"),
        sizeBytes = 1L,
        appPath = "",
        indexed = false,
        addedAt = 0L,
        baseSlot = baseSlot,
        status = "missing",
        symbol = symbol,
    )

    @Test
    fun it1EIt2SaoOMesmoArquivoUnificado() {
        assertNull(repairPublicationMismatch(att("it-1 544.pdf"), "it_T.pdf"))
        assertNull(repairPublicationMismatch(att("it-2 600.pdf"), "it_T.jwpub"))
    }

    @Test
    fun arquivoDeOutraPublicacaoEhRejeitado() {
        assertEquals(
            "it" to "w19.03",
            repairPublicationMismatch(att("it-1 544.pdf"), "w19.03.pdf"),
        )
        assertEquals(
            "be" to "th",
            repairPublicationMismatch(att("be_T.pdf", baseSlot = "be"), "th_T.pdf"),
        )
    }

    @Test
    fun mesmaRevistaAceitaOutroFormatoEMesmaEdicao() {
        assertNull(repairPublicationMismatch(att("w19.03.pdf"), "w19.03.jwpub"))
        assertEquals(
            "w19.03" to "w19.04",
            repairPublicationMismatch(att("w19.03.pdf"), "w19.04.pdf"),
        )
    }

    @Test
    fun baseSlotEhInferidoQuandoAusente() {
        assertEquals("be", filePublicationKeyOrNull("be_T.pdf"))
        assertEquals("s38", filePublicationKeyOrNull("S-38_T_194.docx"))
        assertNull(filePublicationKeyOrNull("arquivo-renomeado.pdf"))
    }

    @Test
    fun anexoDesconhecidoNaoBloqueiaRestauracao() {
        val current = att("arquivo-renomeado.pdf")
        assertNull(repairPublicationMismatch(current, "outro-nome.pdf"))
    }
}
