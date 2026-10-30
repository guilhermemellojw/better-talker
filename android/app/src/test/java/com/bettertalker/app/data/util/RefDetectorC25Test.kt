package com.bettertalker.app.data.util

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2.5 — normalização de revistas no detector, no coletor e no checklist. */
class RefDetectorC25Test {

    private fun magazine(raw: String): RefDetector.DetectedRef =
        RefDetector.detect(raw).single { it.kind == RefDetector.Kind.MAGAZINE }

    private fun attachment(name: String) = AttachmentEntity(
        id = "a1",
        noteId = null,
        fileName = name,
        kind = "jwpub",
        sizeBytes = 0,
        appPath = "",
        indexed = true,
        addedAt = 0,
        status = "ready",
    )

    @Test
    fun detect_g1_16_marcaAmbiguidadeSemEscolherEdicao() {
        val ref = magazine("(g 1/16 5)")
        assertEquals("g|2016|1", ref.editionKey)
        assertTrue(ref.ambiguous)
        assertTrue(ref.ambiguityNote.orEmpty().contains("janeiro/2016"))
        assertTrue(ref.ambiguityNote.orEmpty().contains("edição 1/2016"))
        assertTrue(ref.label.contains("ambíguo"))
    }

    @Test
    fun detect_g1_2016_naoEhAmbiguo() {
        val ref = magazine("(g 1/2016 5)")
        assertEquals("g|2016|1", ref.editionKey)
        assertFalse(ref.ambiguous)
        assertNull(ref.ambiguityNote)
    }

    @Test
    fun detect_g16_1_detectaNumerada() {
        val ref = magazine("(g16.1 5)")
        assertEquals("gn|2016|1", ref.editionKey)
        assertFalse(ref.ambiguous)
        assertEquals("Despertai! N.º 1/2016", ref.label)
    }

    @Test
    fun matchEdition_ambiguoNaoCasaMesmoComArquivo() {
        val att = attachment("g_T_201601.jwpub")
        val exact = magazine("g 1/2016")
        assertEquals("a1", RefDetector.matchEdition(exact, listOf(att))?.id)

        val ambiguous = magazine("g 1/16")
        assertNull(RefDetector.matchEdition(ambiguous, listOf(att)))
        assertNull(RefDetector.matchEdition(ambiguous, listOf(att), includePending = true))
    }

    @Test
    fun resolve_ambiguoSemLinkNemProbe() {
        val ref = magazine("g 1/16")
        val status = RefDetector.resolve(listOf(ref), emptyList()).single()
        assertFalse(status.resolved)
        assertTrue(status.hint.contains("ambígua"))
        assertEquals("", status.downloadUrl)
        assertFalse(status.exact)
    }

    @Test
    fun sourceKeyDistingueAmbiguaDeExata() {
        val ambiguous = magazine("g 1/16")
        val exact = magazine("g 1/2016")
        assertEquals(ambiguous.editionKey, exact.editionKey)
        assertNotEquals(
            NoteRefsCollector.sourceKey(ambiguous),
            NoteRefsCollector.sourceKey(exact),
        )
    }

    @Test
    fun toNoteSources_ambiguoFicaMissingComHint() {
        val ref = magazine("g 1/16")
        val candidate = SourceCandidate(
            key = NoteSourcesResolver.sourceKey(ref),
            ref = ref.raw,
            kind = SourceKind.PUBLICATION,
            publication = ref,
        )
        val source = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = emptyMap(),
            bibleStatuses = emptyMap(),
        ).sources.single()
        assertEquals(SourceStatus.EDITION_MISSING, source.status)
        assertTrue(source.hint.orEmpty().contains("ambígua"))
        assertTrue(source.hint.orEmpty().contains("janeiro/2016"))
    }

    @Test
    fun toNoteSources_exatoContinuaResolvido() {
        val ref = magazine("g 1/2016")
        val candidate = SourceCandidate(
            key = NoteSourcesResolver.sourceKey(ref),
            ref = ref.raw,
            kind = SourceKind.PUBLICATION,
            publication = ref,
        )
        val source = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = mapOf(
                NoteSourcesResolver.sourceKey(ref) to RefDetector.RefStatus(
                    ref = ref,
                    resolved = true,
                    fileName = "g_T_201601.jwpub",
                    contentResolved = true,
                    snippet = "trecho de janeiro de 2016",
                ),
            ),
            bibleStatuses = emptyMap(),
        ).sources.single()
        assertEquals(SourceStatus.RESOLVED, source.status)
    }

    @Test
    fun detectedFromJson_recompoeAmbiguidadeDoRaw() {
        val ref = magazine("g 1/16")
        val back = RefDetector.detectedFromJson(
            RefDetector.detectedToJson(listOf(ref)),
        ).single()
        assertEquals("g|2016|1", back.editionKey)
        assertTrue(back.ambiguous)
        assertTrue(back.ambiguityNote.orEmpty().contains("janeiro/2016"))
    }

    @Test
    fun publicationRefToDetected_usaParserNosSimbolosCanonicos() {
        val w = NoteRefsCollector.publicationRefToDetected(PublicationRef("w94 1/8"))!!
        assertEquals("w|1994|8|1", w.editionKey)
        assertEquals("w", w.pubKey)
        assertEquals("A Sentinela 1/8/1994", w.label)

        val g = NoteRefsCollector.publicationRefToDetected(PublicationRef("g 6/07"))!!
        assertEquals("g|2007|6", g.editionKey)
        assertEquals("g", g.pubKey)

        val gn = NoteRefsCollector.publicationRefToDetected(PublicationRef("gn 1/24"))!!
        assertEquals("gn|2024|1", gn.editionKey)
        assertEquals("gn", gn.pubKey)
        assertEquals("Despertai! N.º 1/2024", gn.label)

        val issue = NoteRefsCollector.publicationRefToDetected(PublicationRef("g16.1"))!!
        assertEquals("gn|2016|1", issue.editionKey)
        assertEquals("Despertai! N.º 1/2016", issue.label)
    }
}
