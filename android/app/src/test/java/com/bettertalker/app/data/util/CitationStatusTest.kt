package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modelo único: edição → texto → ação, compartilhado por UI e prompt. */
class CitationStatusTest {

    private fun pub(
        ref: String = "w25.07 § 8",
        status: SourceStatus = SourceStatus.EDITION_MISSING,
        textStatus: TextStatus = defaultTextStatus(status),
        hint: String? = null,
    ) = NoteSource(
        ref = ref,
        kind = SourceKind.PUBLICATION,
        citation = CitationStatus(editionStatusOf(status), textStatus),
        hint = hint,
        editionKey = "w|2025|7",
        publicationKey = "w25.07",
        publicationLabel = "A Sentinela — julho de 2025",
    )

    @Test
    fun edicaoAusente_acaoBaixar() {
        val c = pub(status = SourceStatus.EDITION_MISSING).citation
        assertEquals(EditionStatus.MISSING, c.edition)
        assertEquals(TextStatus.NOT_FOUND, c.text)
        assertEquals(SourceAction.DOWNLOAD, c.action)
        assertTrue(c.blocksAnswer)
        assertFalse(c.isReady)
    }

    @Test
    fun baixandoEIndexando_acaoAguardar() {
        val downloading = pub(status = SourceStatus.DOWNLOADING).citation
        val indexing = pub(status = SourceStatus.INDEXING).citation
        assertEquals(SourceAction.WAIT, downloading.action)
        assertEquals(SourceAction.WAIT, indexing.action)
        assertTrue(downloading.isPending)
        assertTrue(indexing.isPending)
        assertTrue(downloading.blocksAnswer)
    }

    @Test
    fun indexacaoFalhou_acaoReindexar() {
        val c = pub(status = SourceStatus.INDEX_FAILED).citation
        assertEquals(EditionStatus.INDEX_FAILED, c.edition)
        assertEquals(SourceAction.REINDEX, c.action)
        assertTrue(c.blocksAnswer)
    }

    @Test
    fun formatoNaoSuportado_acaoAbrir() {
        val c = pub(status = SourceStatus.UNSUPPORTED).citation
        assertEquals(EditionStatus.UNSUPPORTED, c.edition)
        assertEquals(SourceAction.OPEN, c.action)
        assertTrue(c.blocksAnswer)
    }

    @Test
    fun edicaoNoAcervoETrechoResolvido_acaoNenhuma() {
        val c = pub(status = SourceStatus.RESOLVED).citation
        assertEquals(EditionStatus.READY, c.edition)
        assertEquals(TextStatus.RESOLVED, c.text)
        assertEquals(SourceAction.NONE, c.action)
        assertTrue(c.isReady)
        assertFalse(c.blocksAnswer)
    }

    @Test
    fun trechoParcial_contaComoTexteUtilizavelMasAbreEdicao() {
        val c = pub(
            status = SourceStatus.RESOLVED,
            textStatus = TextStatus.PARTIAL,
        ).citation
        assertEquals(SourceAction.OPEN, c.action)
        assertTrue(c.isReady)
        assertFalse(c.blocksAnswer)
    }

    @Test
    fun trechoNaoEncontradoComEdicaoPronta_acaoReindexar() {
        val c = pub(status = SourceStatus.TEXT_NOT_FOUND).citation
        assertEquals(EditionStatus.READY, c.edition)
        assertEquals(TextStatus.NOT_FOUND, c.text)
        assertEquals(SourceAction.REINDEX, c.action)
        assertTrue(c.blocksAnswer)
    }

    @Test
    fun referenciaAmbigua_acaoConfirmar() {
        val c = pub(
            status = SourceStatus.EDITION_MISSING,
            textStatus = TextStatus.AMBIGUOUS,
            hint = "g 1/16 pode ser janeiro/2016 ou edição 1/2016.",
        ).citation
        assertEquals(SourceAction.CONFIRM, c.action)
        assertTrue(c.blocksAnswer)
    }

    @Test
    fun motivoDistingueEdicaoAusenteDeTrechoAusente() {
        val missing = pub(status = SourceStatus.EDITION_MISSING)
        val textMissing = pub(
            ref = "w25.07 § 999",
            status = SourceStatus.TEXT_NOT_FOUND,
        )
        assertEquals("edição ausente do acervo", citationReason(missing))
        assertEquals("edição no acervo; trecho não encontrado", citationReason(textMissing))
        assertFalse(citationReason(textMissing).contains("ausente do acervo"))
    }

    @Test
    fun rotulosDeAcaoNaoDizemQueARefNaoExiste() {
        assertEquals("baixar edição", actionLabel(SourceAction.DOWNLOAD))
        assertEquals("reindexar", actionLabel(SourceAction.REINDEX))
        assertEquals("confirmar edição", actionLabel(SourceAction.CONFIRM))
        assertEquals("aguardar", actionLabel(SourceAction.WAIT))
        val line = citationDetailLine(pub(status = SourceStatus.EDITION_MISSING))
        assertTrue(line.contains("ação: baixar edição"))
        assertFalse(line.contains("não existe"))
    }
}
