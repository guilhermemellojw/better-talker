package com.bettertalker.app.data.repo

import com.bettertalker.app.data.util.CitationStatus
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.NoteRefs
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSourcesResolver
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.TextStatus
import com.bettertalker.app.domain.planning.ReferenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1 — citações não somem em silêncio e PARTIAL é fonte.
 */
class CitedSourceRulesTest {

    @Test
    fun partialContaComoFonteUtilizavel() {
        assertTrue(isCitedSourceUsable(ReferenceStatus.RESOLVED))
        assertTrue(isCitedSourceUsable(ReferenceStatus.PARTIAL))
        assertFalse(isCitedSourceUsable(ReferenceStatus.UNRESOLVED))
        assertFalse(isCitedSourceUsable(ReferenceStatus.MISSING_CORPUS))
    }

    @Test
    fun edicaoPresenteSemTrechoNaoDizQueAPublicacaoFalta() {
        val detail = citedUnavailableDetail(
            reference = "w25.07 § 999",
            status = ReferenceStatus.UNRESOLVED,
            editionPresent = true,
        )
        assertEquals("w25.07 § 999 — edição no acervo; trecho não encontrado", detail)
        assertFalse(detail.contains("edição ausente"))
    }

    @Test
    fun edicaoAusenteExplicaQueFaltaAEdicao() {
        val detail = citedUnavailableDetail(
            reference = "w24.01 § 3",
            status = ReferenceStatus.MISSING_CORPUS,
            editionPresent = false,
        )
        assertEquals("w24.01 § 3 — edição ausente do acervo", detail)
    }

    @Test
    fun bibliaDistingueTnmAusenteDeVersiculoAusente() {
        assertEquals(
            "Je 29:11 — TNM ausente",
            citedUnavailableDetail(
                reference = "Je 29:11",
                status = ReferenceStatus.UNRESOLVED,
                editionPresent = false,
                bible = true,
                tnmPresent = false,
            ),
        )
        assertEquals(
            "Je 29:11 — TNM presente; versículo não encontrado",
            citedUnavailableDetail(
                reference = "Je 29:11",
                status = ReferenceStatus.UNRESOLVED,
                editionPresent = true,
                bible = true,
                tnmPresent = true,
            ),
        )
    }

    // ---------- C2.8: o prompt usa o mesmo texto do status da UI ----------

    private fun source(
        promptText: String? = null,
        snippet: String? = null,
        text: TextStatus = TextStatus.RESOLVED,
        kind: SourceKind = SourceKind.PUBLICATION,
    ): NoteSource {
        val ref = RefDetector.DetectedRef(
            raw = "it 544 § 4",
            kind = RefDetector.Kind.BOOK,
            pubKey = "it",
            editionKey = "book|it",
            label = "it 544 § 4",
            page = 544,
            paragraph = 4,
        )
        return NoteSource(
            ref = ref.raw,
            kind = kind,
            citation = CitationStatus(EditionStatus.READY, text),
            refStatus = RefDetector.RefStatus(
                ref = ref,
                resolved = true,
                fileName = "it_T.jwpub",
                contentResolved = text == TextStatus.RESOLVED ||
                    text == TextStatus.PARTIAL,
                contentPartial = text == TextStatus.PARTIAL,
                snippet = snippet,
                promptText = promptText,
            ),
        )
    }

    @Test
    fun promptTextTemPrioridadeSobreSnippet() {
        assertEquals(
            "unidade completa",
            citedPromptText(source(promptText = "unidade completa", snippet = "recorte")),
        )
    }

    @Test
    fun snippetEhFallbackQuandoNaoHaPromptText() {
        assertEquals("recorte", citedPromptText(source(snippet = "recorte")))
    }

    @Test
    fun fonteSemTextoNaoEntraNoPrompt() {
        assertEquals("", citedPromptText(source()))
    }

    @Test
    fun bibliaNaoUsaPromptTextDePublicacao() {
        assertEquals(
            "",
            citedPromptText(source(promptText = "texto", kind = SourceKind.BIBLE)),
        )
    }

    @Test
    fun noteSourcesMarcamPartialQuandoConteudoParcial() {
        val ref = RefDetector.DetectedRef(
            raw = "it 544 § 4",
            kind = RefDetector.Kind.BOOK,
            pubKey = "it",
            editionKey = "book|it",
            label = "it 544 § 4",
            page = 544,
            paragraph = 4,
        )
        val candidate = NoteSourcesResolver.candidatesOf(
            NoteRefs(listOf(ref), emptyList())
        ).single()
        val status = RefDetector.RefStatus(
            ref = ref,
            resolved = true,
            fileName = "it_T.jwpub",
            contentResolved = true,
            contentPartial = true,
            snippet = "recorte",
            promptText = "unidade completa",
        )
        val sources = NoteSourcesResolver.toNoteSources(
            candidates = listOf(candidate),
            publicationStatuses = mapOf(
                NoteSourcesResolver.sourceKey(ref) to status
            ),
            bibleStatuses = emptyMap(),
        )
        assertEquals(TextStatus.PARTIAL, sources.sources.single().citation.text)
        assertTrue(sources.sources.single().citation.isReady)
        assertEquals(
            "unidade completa",
            sources.sources.single().refStatus?.promptText,
        )
    }
}

/**
 * be/th nunca entram como conteúdo no `citedSources`; são orientação.
 */
class GuidePubTrainingTest {

    private fun ref(pubKey: String) = RefDetector.DetectedRef(
        raw = pubKey,
        kind = RefDetector.Kind.BOOK,
        pubKey = pubKey,
        editionKey = "book|$pubKey",
        label = pubKey,
    )

    @Test
    fun beEhGuiaNaoConteudo() {
        assertTrue(isGuidePubRef(ref("be")))
    }

    @Test
    fun thEhGuiaNaoConteudo() {
        assertTrue(isGuidePubRef(ref("th")))
    }

    @Test
    fun publicacaoDeConteudoNaoEhGuia() {
        assertFalse(isGuidePubRef(ref("w")))
        assertFalse(isGuidePubRef(ref("lmd")))
        assertFalse(isGuidePubRef(ref("it")))
    }
}
