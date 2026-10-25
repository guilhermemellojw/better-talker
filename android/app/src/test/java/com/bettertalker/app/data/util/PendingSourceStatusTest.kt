package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Estados transitórios do checklist: Baixando → Indexando → No acervo. */
class PendingSourceStatusTest {

    private fun publicationCandidate(raw: String): Pair<SourceCandidate, RefDetector.DetectedRef> {
        val ref = RefDetector.detect(raw).single()
        return SourceCandidate(
            key = NoteSourcesResolver.sourceKey(ref),
            ref = ref.raw,
            kind = SourceKind.PUBLICATION,
            publication = ref,
        ) to ref
    }

    @Test
    fun publicacaoBaixando_naoEhEdicaoAusente() {
        val (candidate, ref) = publicationCandidate("w25.07 22 §§ 8-9")
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = mapOf(
                candidate.key to RefDetector.RefStatus(
                    ref = ref,
                    resolved = false,
                    downloadUrl = "https://www.jw.org/pt/biblioteca/revistas/sentinela-estudo-julho-2025/",
                ),
            ),
            bibleStatuses = emptyMap(),
            pendingStatuses = mapOf(candidate.key to SourceStatus.DOWNLOADING),
        )
        val source = sources.sources.single()
        assertEquals(SourceStatus.DOWNLOADING, source.status)
        assertEquals(0, sources.resolved)
        assertEquals(1, sources.pending)
        assertTrue(source.status.isPending())
        assertFalse(source.status.isMissing())
        assertTrue(source.hint.orEmpty().contains("Biblioteca"))
    }

    @Test
    fun publicacaoIndexando_mantemTransitorio() {
        val (candidate, ref) = publicationCandidate("lmd lição 7 ponto 4")
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = mapOf(
                candidate.key to RefDetector.RefStatus(ref = ref, resolved = false),
            ),
            bibleStatuses = emptyMap(),
            pendingStatuses = mapOf(candidate.key to SourceStatus.INDEXING),
        )
        assertEquals(SourceStatus.INDEXING, sources.sources.single().status)
        assertTrue(sources.sources.single().hint.orEmpty().contains("Indexando"))
    }

    @Test
    fun publicacaoResolvida_ignoraPending() {
        val (candidate, ref) = publicationCandidate("w25.07 22 §§ 8-9")
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = mapOf(
                candidate.key to RefDetector.RefStatus(
                    ref = ref,
                    resolved = true,
                    fileName = "w25.07.jwpub",
                    contentResolved = true,
                ),
            ),
            bibleStatuses = emptyMap(),
            pendingStatuses = mapOf(candidate.key to SourceStatus.DOWNLOADING),
        )
        assertEquals(SourceStatus.RESOLVED, sources.sources.single().status)
    }

    @Test
    fun bibliaBaixando_naoEhEdicaoAusente() {
        val bible = RefDetector.BibleRef("jer", "Jeremias", 45, 3)
        val candidate = SourceCandidate(
            key = "bible|jeremias|45|3",
            ref = "Jeremias 45:3",
            kind = SourceKind.BIBLE,
            bible = bible,
        )
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = emptyMap(),
            bibleStatuses = mapOf(
                candidate.key to BibleSourceStatus(
                    available = false,
                    verseFound = false,
                    pending = SourceStatus.DOWNLOADING,
                ),
            ),
        )
        assertEquals(SourceStatus.DOWNLOADING, sources.sources.single().status)
        assertEquals("TNM", sources.sources.single().publicationLabel)
        assertEquals(45, sources.sources.single().chapter)
        assertEquals(3, sources.sources.single().verse)
    }

    @Test
    fun bibliaIndexando_mostraIndexando() {
        val bible = RefDetector.BibleRef("jer", "Jeremias", 45, 3)
        val candidate = SourceCandidate(
            key = "bible|jeremias|45|3",
            ref = "Jeremias 45:3",
            kind = SourceKind.BIBLE,
            bible = bible,
        )
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = emptyMap(),
            bibleStatuses = mapOf(
                candidate.key to BibleSourceStatus(
                    available = false,
                    verseFound = false,
                    pending = SourceStatus.INDEXING,
                ),
            ),
        )
        assertEquals(SourceStatus.INDEXING, sources.sources.single().status)
    }

    @Test
    fun rotulosDeStatusSaoEstaveis() {
        assertEquals("No acervo", com.bettertalker.app.ui.components.sourceStatusLabel(SourceStatus.RESOLVED))
        assertEquals(
            "Edição não importada",
            com.bettertalker.app.ui.components.sourceStatusLabel(SourceStatus.EDITION_MISSING),
        )
        assertEquals(
            "Texto não encontrado",
            com.bettertalker.app.ui.components.sourceStatusLabel(SourceStatus.TEXT_NOT_FOUND),
        )
        assertEquals(
            "Baixando…",
            com.bettertalker.app.ui.components.sourceStatusLabel(SourceStatus.DOWNLOADING),
        )
        assertEquals(
            "Indexando…",
            com.bettertalker.app.ui.components.sourceStatusLabel(SourceStatus.INDEXING),
        )
    }
}
