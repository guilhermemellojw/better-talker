package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5e.1: testes do [DossierFidelityCheck] (puro, regex).
 */
class DossierFidelityCheckTest {

    private fun dossier(
        bibleTexts: List<ResolvedBibleText> = emptyList(),
        publicationTexts: List<ResolvedPublicationText> = emptyList(),
        unresolvedRefs: List<String> = emptyList(),
    ): Dossier = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
            title = "Título teste", minutes = 5, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0, updatedAt = 0,
        ),
        currentSubPoint = null,
        selectedText = null,
        fullContentHtml = "",
        overview = emptyList(),
        bibleTexts = bibleTexts,
        publicationTexts = publicationTexts,
        methodPrinciples = emptyList(),
        unresolvedRefs = unresolvedRefs,
        transitionContext = null,
    )

    @Test
    fun check_validDraft_okIsTrue() {
        val d = dossier(
            bibleTexts = listOf(
                ResolvedBibleText("Gên 3:6", "texto", ReferenceStatus.RESOLVED)
            ),
        )
        val r = DossierFidelityCheck.check(
            "<p>Como diz Gên 3:6, houve desobediência.</p>",
            listOf("Gên 3:6"),
            d,
        )
        assertTrue(r.ok)
    }

    @Test
    fun check_inventedBibleRef_reported() {
        val d = dossier()
        val r = DossierFidelityCheck.check(
            "<p>Veja Gên 99:99 para saber mais.</p>",
            emptyList(),
            d,
        )
        assertFalse(r.ok)
        assertTrue(r.inventedBibleRefs.isNotEmpty())
    }

    @Test
    fun check_inventedPublicationRef_reported() {
        val d = dossier()
        val r = DossierFidelityCheck.check(
            "<p>Conforme w99.01, devemos agir.</p>",
            emptyList(),
            d,
        )
        assertFalse(r.ok)
        assertTrue(r.inventedPublicationRefs.contains("w99.01"))
    }

    @Test
    fun check_usedSourcesOutsideDossier_reported() {
        val d = dossier(
            bibleTexts = listOf(
                ResolvedBibleText("Gên 3:6", "texto", ReferenceStatus.RESOLVED)
            ),
        )
        val r = DossierFidelityCheck.check(
            "<p>Texto sem citações.</p>",
            listOf("Gên 99:99"),
            d,
        )
        assertFalse(r.ok)
        assertEquals(listOf("gên 99:99"), r.usedSourcesOutsideDossier)
    }

    @Test
    fun check_unresolvedRefCited_reported() {
        val d = dossier(unresolvedRefs = listOf("Gên 99:99"))
        val r = DossierFidelityCheck.check(
            "<p>Como diz Gên 99:99, devemos obedecer.</p>",
            emptyList(),
            d,
        )
        assertFalse(r.ok)
        assertTrue(r.unresolvedRefsCited.contains("gên 99:99"))
    }

    @Test
    fun check_emptyDossier_emptyReport_okIsTrue() {
        val r = DossierFidelityCheck.check(
            "<p>Texto simples, sem referências.</p>",
            emptyList(),
            dossier(),
        )
        assertTrue(r.ok)
        assertTrue(r.inventedBibleRefs.isEmpty())
        assertTrue(r.inventedPublicationRefs.isEmpty())
        assertTrue(r.usedSourcesOutsideDossier.isEmpty())
        assertTrue(r.unresolvedRefsCited.isEmpty())
    }

    @Test
    fun check_normalizedRefMatchesCaseInsensitive() {
        val d = dossier(
            bibleTexts = listOf(
                ResolvedBibleText("Gên 3:6", "texto", ReferenceStatus.RESOLVED)
            ),
        )
        val r = DossierFidelityCheck.check(
            "<p>como diz gên 3:6, houve desobediência.</p>",
            listOf("GÊN 3:6"),
            d,
        )
        assertTrue(r.ok)
    }

    // ---------- 3.5e.2: usedSources com sufixo de página/parágrafo ----------

    @Test
    fun check_usedSourceWithPage_matchesBaseSymbol() {
        val d = dossier(
            publicationTexts = listOf(
                ResolvedPublicationText(
                    PublicationRef("be"), "texto", ReferenceStatus.RESOLVED
                )
            ),
        )
        val r = DossierFidelityCheck.check(
            "<p>Texto sem citações.</p>",
            listOf("be p. 52 §3"),
            d,
        )
        assertTrue("falso positivo: ${r.usedSourcesOutsideDossier}", r.ok)
    }

    @Test
    fun check_usedSourceWithParagraphOnly_matches() {
        val d = dossier(
            publicationTexts = listOf(
                ResolvedPublicationText(
                    PublicationRef("w21.08"), "texto", ReferenceStatus.RESOLVED
                )
            ),
        )
        val r = DossierFidelityCheck.check(
            "<p>Texto sem citações.</p>",
            listOf("w21.08 §13"),
            d,
        )
        assertTrue("falso positivo: ${r.usedSourcesOutsideDossier}", r.ok)
    }

    @Test
    fun check_bibleRefInUsedSources_unchanged() {
        val d = dossier(
            bibleTexts = listOf(
                ResolvedBibleText("Gên 3:6", "texto", ReferenceStatus.RESOLVED)
            ),
        )
        val r = DossierFidelityCheck.check(
            "<p>Texto sem citações.</p>",
            listOf("Gên 3:6"),
            d,
        )
        assertTrue("falso positivo: ${r.usedSourcesOutsideDossier}", r.ok)
    }
}
