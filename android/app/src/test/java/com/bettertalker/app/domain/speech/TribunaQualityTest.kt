package com.bettertalker.app.domain.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v5.1 — qualidade do mapa de lembretes e motivos exibidos no badge. Puro. */
class TribunaQualityTest {

    private fun capsule(
        id: String = "s1",
        anchor: String = "Âncora",
        transition: String? = null,
        cues: List<TribunaCue> = listOf(
            TribunaCue(TribunaCueKind.REASONING, "O argumento"),
        ),
        keyTerms: List<String> = listOf("termo"),
        version: Int = 2,
    ) = TribunaCapsule(
        sectionId = id,
        mainIdea = "Ideia",
        anchorPhrase = anchor,
        transition = transition,
        cues = cues,
        keyTerms = keyTerms,
        cueSheetVersion = version,
    )

    @Test
    fun semCapsulaEhMissing() {
        val q = tribunaMapQuality(null, sectionHasRefs = false, isLastSection = true)
        assertEquals(TribunaMapStatus.MISSING, q.status)
        assertTrue(q.reasons.isEmpty())
    }

    @Test
    fun capsulaAntigaSemCuesEhLegado() {
        val q = tribunaMapQuality(
            capsule(cues = emptyList(), version = 0),
            sectionHasRefs = false,
            isLastSection = true,
        )
        assertEquals(TribunaMapStatus.LEGACY, q.status)
        assertFalse(q.complete)
    }

    @Test
    fun folhaV2VaziaNaoEhLegadoEMostraSemLembretes() {
        val q = tribunaMapQuality(
            capsule(cues = emptyList(), version = 2),
            sectionHasRefs = false,
            isLastSection = true,
        )
        assertEquals(TribunaMapStatus.INCOMPLETE, q.status)
        assertTrue(q.reasons.contains("sem lembretes"))
    }

    @Test
    fun mapaCompletoMinimoNaoTemMotivos() {
        val q = tribunaMapQuality(capsule(), sectionHasRefs = false, isLastSection = true)
        assertEquals(TribunaMapStatus.COMPLETE, q.status)
        assertTrue(q.complete)
        assertTrue(q.reasons.isEmpty())
        assertTrue(q.warnings.isEmpty())
    }

    @Test
    fun keyTermsVazioEhAvisoENaoBloqueiaCompletude() {
        val q = tribunaMapQuality(
            capsule(keyTerms = emptyList()),
            sectionHasRefs = false,
            isLastSection = true,
        )
        assertEquals(TribunaMapStatus.COMPLETE, q.status)
        assertEquals(listOf("sem termos-chave"), q.warnings)
    }

    @Test
    fun motivosAcumulamAncoraCueVaziaTransicaoERefs() {
        val q = tribunaMapQuality(
            capsule(
                anchor = " ",
                cues = listOf(
                    TribunaCue(TribunaCueKind.HOOK, "Abertura"),
                    TribunaCue(TribunaCueKind.REASONING, " "),
                ),
                keyTerms = emptyList(),
            ),
            sectionHasRefs = true,
            isLastSection = false,
        )
        assertEquals(TribunaMapStatus.INCOMPLETE, q.status)
        assertTrue(q.reasons.contains("sem âncora"))
        assertTrue(q.reasons.contains("lembrete vazio"))
        assertTrue(q.reasons.contains("sem transição"))
        assertTrue(q.reasons.contains("sem refs"))
        assertEquals(listOf("sem termos-chave"), q.warnings)
    }

    @Test
    fun transicaoNoCampoOuEmLembreteSatisfaz() {
        val noCampo = tribunaMapQuality(
            capsule(transition = "Para a próxima"),
            sectionHasRefs = false,
            isLastSection = false,
        )
        val noLembrete = tribunaMapQuality(
            capsule(
                cues = listOf(
                    TribunaCue(TribunaCueKind.REASONING, "Argumento"),
                    TribunaCue(TribunaCueKind.TRANSITION, "Agora vejamos"),
                ),
            ),
            sectionHasRefs = false,
            isLastSection = false,
        )
        assertEquals(TribunaMapStatus.COMPLETE, noCampo.status)
        assertEquals(TribunaMapStatus.COMPLETE, noLembrete.status)
    }

    @Test
    fun refsEmQualquerLembreteSatisfazemSecaoComRefs() {
        val q = tribunaMapQuality(
            capsule(
                cues = listOf(
                    TribunaCue(
                        kind = TribunaCueKind.SCRIPTURE,
                        text = "Jeová ordena",
                        refs = listOf("Sal 103:20"),
                    ),
                ),
            ),
            sectionHasRefs = true,
            isLastSection = true,
        )
        assertEquals(TribunaMapStatus.COMPLETE, q.status)
        assertFalse(q.reasons.contains("sem refs"))
    }

    @Test
    fun entregaSozinhaNaoEhConteudo() {
        val q = tribunaMapQuality(
            capsule(
                cues = listOf(
                    TribunaCue(TribunaCueKind.DELIVERY, "Pausa de 2 segundos"),
                ),
            ),
            sectionHasRefs = false,
            isLastSection = true,
        )
        assertEquals(TribunaMapStatus.INCOMPLETE, q.status)
        assertTrue(q.reasons.contains("só entrega"))
    }

    @Test
    fun entregaComConteudoNaoBloqueia() {
        val q = tribunaMapQuality(
            capsule(
                cues = listOf(
                    TribunaCue(TribunaCueKind.REASONING, "Argumento"),
                    TribunaCue(TribunaCueKind.DELIVERY, "Pausa de 2 segundos"),
                ),
            ),
            sectionHasRefs = false,
            isLastSection = true,
        )
        assertEquals(TribunaMapStatus.COMPLETE, q.status)
    }
}
