package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AttachmentEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentAttentionTest {

    private fun att(
        id: String,
        status: String,
        fileName: String,
        baseSlot: String? = null,
        sha256: String? = null,
        indexed: Boolean = status == "ready",
        appPath: String = "",
        noteId: String? = null,
    ) = AttachmentEntity(
        id = id,
        noteId = noteId,
        fileName = fileName,
        kind = "pdf",
        sizeBytes = 1L,
        appPath = appPath,
        indexed = indexed,
        addedAt = 0L,
        baseSlot = baseSlot,
        status = status,
        sha256 = sha256,
    )

    @Test
    fun placeholderComMesmoShaSomeQuandoHaReady() {
        val ready = att("ready", "ready", "w25.01.pdf", sha256 = "abc123")
        val missing = att("missing", "missing", "copia.pdf", sha256 = "abc123")

        assertTrue(attentionAttachments(listOf(ready, missing)).isEmpty())
    }

    @Test
    fun placeholderComMesmaPublicacaoSomeQuandoHaReady() {
        val ready = att("ready", "ready", "be_T_1.pdf", baseSlot = "be")
        val missing = att("missing", "missing", "be_T_2.pdf", baseSlot = "be")

        assertTrue(attentionAttachments(listOf(ready, missing)).isEmpty())
    }

    @Test
    fun placeholderComMesmoNomeNormalizadoSomeQuandoHaReady() {
        val ready = att("ready", "ready", "Meu Arquivo.pdf")
        val failed = att("failed", "failed", "  meu arquivo.PDF ")

        assertTrue(attentionAttachments(listOf(ready, failed)).isEmpty())
    }

    @Test
    fun formatoDiferenteDaMesmaPublicacaoContinuaNaAtencao() {
        val ready = att("ready", "ready", "be_T.epub", baseSlot = "be")
            .copy(kind = "epub")
        val failed = att("failed", "failed", "be_T.jwpub", baseSlot = "be")
            .copy(kind = "jwpub")

        assertEquals(listOf(failed), attentionAttachments(listOf(ready, failed)))
    }

    @Test
    fun edicaoDiferenteContinuaNaAtencao() {
        val ready = att("ready", "ready", "w25.01.pdf")
        val missing = att("missing", "missing", "w25.02.pdf")

        assertEquals(listOf(missing), attentionAttachments(listOf(ready, missing)))
    }

    @Test
    fun missingSozinhoContinuaNaAtencao() {
        val missing = att("missing", "missing", "w25.01.pdf")

        assertEquals(listOf(missing), attentionAttachments(listOf(missing)))
    }

    @Test
    fun failedSozinhoContinuaNaAtencao() {
        val failed = att("failed", "failed", "w25.01.pdf")

        assertEquals(listOf(failed), attentionAttachments(listOf(failed)))
    }

    @Test
    fun readyNaoIndexadoNaoEscondePlaceholder() {
        val ready = att("ready", "ready", "Meu Arquivo.pdf", indexed = false)
        val missing = att("missing", "missing", "meu arquivo.pdf")

        assertEquals(listOf(missing), attentionAttachments(listOf(ready, missing)))
    }

    @Test
    fun emAndamentoNaoEscondePlaceholder() {
        val indexing = att("indexing", "indexing", "Meu Arquivo.pdf")
        val missing = att("missing", "missing", "meu arquivo.pdf")

        assertEquals(listOf(missing), attentionAttachments(listOf(indexing, missing)))
    }

    @Test
    fun importReutilizaMissingComMesmoNome() {
        val missing = att("missing", "missing", "Meu Arquivo.pdf")

        val picked = chooseImportPlaceholder(listOf(missing), "meu arquivo.PDF", "pdf")

        assertSame(missing, picked)
    }

    @Test
    fun importReutilizaFailedComMesmoNome() {
        val failed = att("failed", "failed", "w25.01.pdf")

        val picked = chooseImportPlaceholder(listOf(failed), "w25.01.pdf", "pdf")

        assertSame(failed, picked)
    }

    @Test
    fun importReutilizaPlaceholderDaMesmaPublicacaoETipo() {
        val missing = att("missing", "missing", "be_T_1.pdf", baseSlot = "be")

        val picked = chooseImportPlaceholder(listOf(missing), "be_T_2.pdf", "pdf")

        assertSame(missing, picked)
    }

    @Test
    fun importNaoReutilizaPublicacaoDeOutroTipo() {
        val missing = att("missing", "missing", "be_T_1.epub", baseSlot = "be")
            .copy(kind = "epub")

        assertNull(chooseImportPlaceholder(listOf(missing), "be_T_2.pdf", "pdf"))
    }

    @Test
    fun importNaoReutilizaEdicaoDiferente() {
        val missing = att("missing", "missing", "w25.02.pdf")

        assertNull(chooseImportPlaceholder(listOf(missing), "w25.01.pdf", "pdf"))
    }

    @Test
    fun importNaoReutilizaLinhaComArquivoValido() {
        val valid = File.createTempFile("att-", ".pdf")
        try {
            val failed = att("failed", "failed", "w25.01.pdf", appPath = valid.absolutePath)

            assertNull(chooseImportPlaceholder(listOf(failed), "w25.01.pdf", "pdf"))
        } finally {
            valid.delete()
        }
    }

    @Test
    fun importNaoReutilizaAnexoReady() {
        val ready = att("ready", "ready", "w25.01.pdf")

        assertNull(chooseImportPlaceholder(listOf(ready), "w25.01.pdf", "pdf"))
    }

    @Test
    fun atencaoVaziaQuandoNaoHaPlaceholders() {
        val ready = att("ready", "ready", "w25.01.pdf")
        val downloading = att("downloading", "downloading", "w25.02.pdf")

        assertTrue(attentionAttachments(listOf(ready, downloading)).isEmpty())
    }

    @Test
    fun chaveAmplaDeRevistaNaoEscondeEdicaoDiferente() {
        val ready = att("ready", "ready", "w_T.pdf")
        val failed = att("failed", "failed", "w_T_20080615.jwpub")

        assertEquals(listOf(failed), attentionAttachments(listOf(ready, failed)))
    }

    @Test
    fun importPreenchePlaceholderPreservandoIdEVinculo() {
        val placeholder = att("att-1", "missing", "antigo.docx", noteId = "note-1")

        val filled = fillAttachmentFromImport(
            existing = placeholder,
            displayName = "S-34_T_035.docx",
            kind = "docx",
            appPath = "/tmp/att-1.docx",
            sizeBytes = 99L,
            sha256 = "hash-1",
            noteId = "note-2",
        )

        assertEquals("att-1", filled.id)
        assertEquals("note-1", filled.noteId)
        assertEquals("S-34_T_035.docx", filled.fileName)
        assertEquals("docx", filled.kind)
        assertEquals("/tmp/att-1.docx", filled.appPath)
        assertEquals(99L, filled.sizeBytes)
        assertEquals("hash-1", filled.sha256)
        assertEquals("indexing", filled.status)
        assertFalse(filled.indexed)
        assertNull(filled.error)
    }

    @Test
    fun importAdotaNotaQuandoPlaceholderNaoTemVinculo() {
        val placeholder = att("att-1", "missing", "w25.01.pdf")

        val filled = fillAttachmentFromImport(
            existing = placeholder,
            displayName = "w25.01.pdf",
            kind = "pdf",
            appPath = "/tmp/att-1.pdf",
            sizeBytes = 10L,
            sha256 = "hash-1",
            noteId = "note-2",
        )

        assertEquals("note-2", filled.noteId)
    }

    @Test
    fun importPreservaBaseSlotExistente() {
        val placeholder = att("att-1", "missing", "be_T_1.pdf", baseSlot = "be")

        val filled = fillAttachmentFromImport(
            existing = placeholder,
            displayName = "be_T_2.pdf",
            kind = "pdf",
            appPath = "/tmp/att-1.pdf",
            sizeBytes = 10L,
            sha256 = "hash-1",
            noteId = null,
        )

        assertEquals("be", filled.baseSlot)
    }

    @Test
    fun importPreencheBaseSlotQuandoAusente() {
        val placeholder = att("att-1", "missing", "arquivo.pdf")

        val filled = fillAttachmentFromImport(
            existing = placeholder,
            displayName = "be_T_2.pdf",
            kind = "pdf",
            appPath = "/tmp/att-1.pdf",
            sizeBytes = 10L,
            sha256 = "hash-1",
            noteId = null,
        )

        assertEquals("be", filled.baseSlot)
    }
}
