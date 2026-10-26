package com.bettertalker.app.data.repo

import com.bettertalker.app.data.util.RefDetector
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
