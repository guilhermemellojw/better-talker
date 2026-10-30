package com.bettertalker.app.data.util

import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A2 — checklist de fontes por nota (puro). */
class NoteSourcesResolverTest {

    private fun section(
        bibleRefs: List<String> = emptyList(),
        publicationRefs: List<PublicationRef> = emptyList(),
        contentHtml: String = "",
        title: String = "Tópico",
    ) = SpeechSection(
        id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
        title = title, minutes = 5, contentHtml = contentHtml,
        bibleRefs = bibleRefs, publicationRefs = publicationRefs,
        methodPrinciple = null, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun collect_juntaOutlineSecoesETextoEDeduplica() {
        val lmd = RefDetector.detect("lmd lição 7 ponto 4")
        val outline = RefDetector.detectedToJson(lmd)
        val candidates = NoteSourcesResolver.collectCandidates(
            outlineRefsJson = outline,
            sections = listOf(
                section(
                    bibleRefs = listOf("Gên 3:6"),
                    publicationRefs = listOf(PublicationRef("lmd", chapter = "lição 7", paragraph = 4)),
                    contentHtml = "<p>Veja lmd lição 7 ponto 4.</p>",
                ),
            ),
            subPoints = listOf(
                SubPoint(
                    id = "sp1", sectionId = "s1", order = 0,
                    outlineText = "Rom 5:12", bibleRefs = listOf("Rom 5:12"),
                    publicationRefs = emptyList(), instruction = null,
                    developedHtml = "", createdAt = 0, updatedAt = 0,
                ),
            ),
            noteText = "Gên 3:6 e lmd lição 7 ponto 4.",
        )

        // lmd lição aparece no outline, na seção (JSON) e no texto — mas o raw
        // é o mesmo, então deduplica para 1.
        assertEquals(1, candidates.count { it.publication?.raw == "lmd lição 7 ponto 4" })
        // Bíblia aparece no section.bibleRefs e no texto — deduplica para 1.
        assertEquals(1, candidates.count { it.bible?.chapter == 3 && it.bible?.verse == 6 })
        // S-34 guarda refs nos subpontos: Rom 5:12 também entra.
        assertTrue(candidates.any { it.bible?.chapter == 5 && it.bible?.verse == 12 })
        assertTrue(candidates.any { it.kind == SourceKind.PUBLICATION })
        assertTrue(candidates.any { it.kind == SourceKind.BIBLE })
    }

    @Test
    fun collect_dedupBible_porNomeCanonico() {
        val candidates = NoteSourcesResolver.collectCandidates(
            outlineRefsJson = "",
            sections = listOf(section(bibleRefs = listOf("Jer. 45:3"))),
            noteText = "Jeremias 45:3",
        )
        assertEquals(
            1,
            candidates.count {
                it.kind == SourceKind.BIBLE &&
                    it.bible?.chapter == 45 && it.bible?.verse == 3
            },
        )
    }

    @Test
    fun collect_incluiTituloDaNota() {
        val candidates = NoteSourcesResolver.collectCandidates(
            outlineRefsJson = "",
            sections = emptyList(),
            noteText = "",
            noteTitle = "Ter esperança (w25.07 20 § 2)",
        )
        assertEquals(1, candidates.count { it.publication?.paragraph == 2 })
    }

    @Test
    fun publicationRefToDetected_lmdApendicePreservaUnidadeEPonto() {
        val detected = NoteSourcesResolver.publicationRefToDetected(
            PublicationRef("lmd", chapter = "Apêndice A", paragraph = 20),
        )!!
        assertEquals("lmd", detected.pubKey)
        assertEquals("book|lmd", detected.editionKey)
        assertEquals("Apêndice A", detected.article)
        assertEquals("Apêndice A", detected.chapter)
        assertEquals(20, detected.paragraph)
    }

    @Test
    fun publicationRefToDetected_revistaEPagina() {
        val w = NoteSourcesResolver.publicationRefToDetected(
            PublicationRef("w25.07", paragraph = 8),
        )!!
        assertEquals(RefDetector.Kind.MAGAZINE, w.kind)
        assertEquals("w|2025|7", w.editionKey)
        assertEquals(8, w.paragraph)

        val th = NoteSourcesResolver.publicationRefToDetected(
            PublicationRef("th", page = 52),
        )!!
        assertEquals(RefDetector.Kind.BOOK, th.kind)
        assertEquals("th", th.pubKey)
        assertEquals(52, th.page)
    }

    @Test
    fun publicationRefToDetected_simboloDesconhecido_naoInventa() {
        assertNull(NoteSourcesResolver.publicationRefToDetected(PublicationRef("xyz")))
    }

    @Test
    fun toNoteSources_classificaResolvedMissingETextNotFound() {
        val resolvedRef = RefDetector.detect("lmd apêndice A ponto 20").single()
        val missingRef = RefDetector.detect("w25.07 5 § 8").single()
        val textMissingRef = RefDetector.detect("lmd lição 7 ponto 4").single()
        val candidates = listOf(
            SourceCandidate(
                key = NoteSourcesResolver.sourceKey(resolvedRef),
                ref = resolvedRef.raw,
                kind = SourceKind.PUBLICATION,
                publication = resolvedRef,
            ),
            SourceCandidate(
                key = NoteSourcesResolver.sourceKey(missingRef),
                ref = missingRef.raw,
                kind = SourceKind.PUBLICATION,
                publication = missingRef,
            ),
            SourceCandidate(
                key = NoteSourcesResolver.sourceKey(textMissingRef),
                ref = textMissingRef.raw,
                kind = SourceKind.PUBLICATION,
                publication = textMissingRef,
            ),
            SourceCandidate(
                key = "bible|gen|3|6",
                ref = "Gên 3:6",
                kind = SourceKind.BIBLE,
                bible = RefDetector.BibleRef("gen", "Gên", 3, 6),
            ),
            SourceCandidate(
                key = "bible|gen|3|7",
                ref = "Gên 3:7",
                kind = SourceKind.BIBLE,
                bible = RefDetector.BibleRef("gen", "Gên", 3, 7),
            ),
        )
        val statuses = mapOf(
            NoteSourcesResolver.sourceKey(resolvedRef) to RefDetector.RefStatus(
                ref = resolvedRef,
                resolved = true,
                fileName = "lmd_T.jwpub",
                contentResolved = true,
            ),
            NoteSourcesResolver.sourceKey(missingRef) to RefDetector.RefStatus(
                ref = missingRef,
                resolved = false,
                downloadUrl = "https://jw.org/w25.07",
                hint = "Baixe a edição.",
            ),
            NoteSourcesResolver.sourceKey(textMissingRef) to RefDetector.RefStatus(
                ref = textMissingRef,
                resolved = true,
                fileName = "lmd_T.jwpub",
                contentResolved = false,
                contentWarning = RefDetector.CONTENT_WARNING,
            ),
        )
        val bible = mapOf(
            "bible|gen|3|6" to BibleSourceStatus(available = true, verseFound = true),
            "bible|gen|3|7" to BibleSourceStatus(available = true, verseFound = false),
        )

        val sources = NoteSourcesResolver.toNoteSources(candidates, statuses, bible)

        assertEquals(5, sources.total)
        assertEquals(2, sources.resolved) // lmd apêndice + Gên 3:6
        assertEquals(
            SourceStatus.RESOLVED,
            sources.sources.first { it.ref == resolvedRef.raw }.status,
        )
        val missing = sources.sources.first { it.ref == missingRef.raw }
        assertEquals(SourceStatus.EDITION_MISSING, missing.status)
        assertEquals("https://jw.org/w25.07", missing.downloadUrl)
        val textMissing = sources.sources.first { it.ref == textMissingRef.raw }
        assertEquals(SourceStatus.TEXT_NOT_FOUND, textMissing.status)
        assertEquals(
            SourceStatus.TEXT_NOT_FOUND,
            sources.sources.first { it.ref == "Gên 3:7" }.status,
        )
    }

    @Test
    fun toNoteSources_expoeEditionStatusTextStatusEAction() {
        val resolvedRef = RefDetector.detect("w25.07 22 § 8").single()
        val missingRef = RefDetector.detect("w25.07 21 § 9").single()
        val textMissingRef = RefDetector.detect("lmd lição 7 § 4").single()
        val failedRef = RefDetector.detect("w24.01 § 3").single()
        val ambiguousRef = RefDetector.detect("g 1/16").single()
        val candidates = listOf(resolvedRef, missingRef, textMissingRef, failedRef, ambiguousRef)
            .map { ref ->
                SourceCandidate(
                    key = NoteSourcesResolver.sourceKey(ref),
                    ref = ref.raw,
                    kind = SourceKind.PUBLICATION,
                    publication = ref,
                )
            }
        val statuses = mapOf(
            NoteSourcesResolver.sourceKey(resolvedRef) to RefDetector.RefStatus(
                ref = resolvedRef, resolved = true, contentResolved = true,
            ),
            NoteSourcesResolver.sourceKey(missingRef) to RefDetector.RefStatus(
                ref = missingRef, resolved = false,
            ),
            NoteSourcesResolver.sourceKey(textMissingRef) to RefDetector.RefStatus(
                ref = textMissingRef, resolved = true, contentResolved = false,
            ),
            NoteSourcesResolver.sourceKey(failedRef) to RefDetector.RefStatus(
                ref = failedRef, resolved = false,
            ),
            NoteSourcesResolver.sourceKey(ambiguousRef) to RefDetector.RefStatus(
                ref = ambiguousRef, resolved = false,
            ),
        )

        val sources = NoteSourcesResolver.toNoteSources(
            candidates = candidates,
            publicationStatuses = statuses,
            bibleStatuses = emptyMap(),
            pendingStatuses = mapOf(
                NoteSourcesResolver.sourceKey(failedRef) to SourceStatus.INDEX_FAILED,
            ),
        )

        val resolved = sources.sources.first { it.ref == resolvedRef.raw }
        assertEquals(EditionStatus.READY, resolved.citation.edition)
        assertEquals(TextStatus.RESOLVED, resolved.citation.text)
        assertEquals(SourceAction.NONE, resolved.citation.action)

        val missing = sources.sources.first { it.ref == missingRef.raw }
        assertEquals(EditionStatus.MISSING, missing.citation.edition)
        assertEquals(SourceAction.DOWNLOAD, missing.citation.action)

        val textMissing = sources.sources.first { it.ref == textMissingRef.raw }
        assertEquals(EditionStatus.READY, textMissing.citation.edition)
        assertEquals(TextStatus.NOT_FOUND, textMissing.citation.text)
        assertEquals(SourceAction.REINDEX, textMissing.citation.action)

        val failed = sources.sources.first { it.ref == failedRef.raw }
        assertEquals(EditionStatus.INDEX_FAILED, failed.citation.edition)
        assertEquals(SourceAction.REINDEX, failed.citation.action)

        val ambiguous = sources.sources.first { it.ref == ambiguousRef.raw }
        assertEquals(TextStatus.AMBIGUOUS, ambiguous.citation.text)
        assertEquals(SourceAction.CONFIRM, ambiguous.citation.action)
    }

    @Test
    fun toNoteSources_bibliaSemTnm_acaoBaixarEComVersiculoAusente_reindexar() {
        val missingBible = SourceCandidate(
            key = "bible|gen|3|6",
            ref = "Gên 3:6",
            kind = SourceKind.BIBLE,
            bible = RefDetector.BibleRef("gen", "Gên", 3, 6),
        )
        val missingVerse = SourceCandidate(
            key = "bible|gen|3|7",
            ref = "Gên 3:7",
            kind = SourceKind.BIBLE,
            bible = RefDetector.BibleRef("gen", "Gên", 3, 7),
        )
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = listOf(missingBible, missingVerse),
            publicationStatuses = emptyMap(),
            bibleStatuses = mapOf(
                missingBible.key to BibleSourceStatus(available = false, verseFound = false),
                missingVerse.key to BibleSourceStatus(available = true, verseFound = false),
            ),
        )

        val noTnm = sources.sources.first { it.ref == "Gên 3:6" }
        assertEquals(EditionStatus.MISSING, noTnm.citation.edition)
        assertEquals(SourceAction.DOWNLOAD, noTnm.citation.action)
        assertEquals("TNM ausente", citationReason(noTnm))

        val noVerse = sources.sources.first { it.ref == "Gên 3:7" }
        assertEquals(EditionStatus.READY, noVerse.citation.edition)
        assertEquals(SourceAction.REINDEX, noVerse.citation.action)
        assertEquals("TNM presente; versículo não encontrado", citationReason(noVerse))
    }
}
