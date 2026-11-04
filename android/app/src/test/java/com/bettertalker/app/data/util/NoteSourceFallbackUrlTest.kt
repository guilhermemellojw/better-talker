package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1/A2: toda fonte faltante precisa abrir a página certa, sem beco. */
class NoteSourceFallbackUrlTest {

    private fun candidate(raw: String): SourceCandidate {
        val ref = RefDetector.detect(raw).single()
        return SourceCandidate(
            key = NoteSourcesResolver.sourceKey(ref),
            ref = ref.raw,
            kind = SourceKind.PUBLICATION,
            publication = ref,
        )
    }

    @Test
    fun statusAusenteAindaGeraUrlDaPublicacao() {
        val candidate = candidate("w25.07 22 § 8")
        val source = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = emptyMap(),
            bibleStatuses = emptyMap(),
        ).sources.single()

        assertEquals(SourceStatus.EDITION_MISSING, source.status)
        assertTrue(source.downloadUrl.orEmpty().contains("sentinela-estudo-julho-2025"))
    }

    @Test
    fun textoNaoEncontradoGeraUrlParaConferirEdicao() {
        val candidate = candidate("w25.07 22 § 8")
        val ref = candidate.publication!!
        val source = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = mapOf(
                candidate.key to RefDetector.RefStatus(
                    ref = ref,
                    resolved = true,
                    contentResolved = false,
                ),
            ),
            bibleStatuses = emptyMap(),
        ).sources.single()

        assertEquals(SourceStatus.TEXT_NOT_FOUND, source.status)
        assertTrue(source.downloadUrl.orEmpty().contains("sentinela-estudo-julho-2025"))
    }

    @Test
    fun indexacaoFalhouSemRefStatusAindaGeraUrl() {
        val candidate = candidate("w25.07 22 § 8")
        val source = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = emptyMap(),
            bibleStatuses = emptyMap(),
            pendingStatuses = mapOf(candidate.key to SourceStatus.INDEX_FAILED),
        ).sources.single()

        assertEquals(SourceStatus.INDEX_FAILED, source.status)
        assertTrue(source.downloadUrl.orEmpty().contains("sentinela-estudo-julho-2025"))
    }

    @Test
    fun formatoNaoSuportadoSemRefStatusAindaGeraUrl() {
        val candidate = candidate("w25.07 22 § 8")
        val source = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = emptyMap(),
            bibleStatuses = emptyMap(),
            pendingStatuses = mapOf(candidate.key to SourceStatus.UNSUPPORTED),
        ).sources.single()

        assertEquals(SourceStatus.UNSUPPORTED, source.status)
        assertTrue(source.downloadUrl.orEmpty().contains("sentinela-estudo-julho-2025"))
    }
}
