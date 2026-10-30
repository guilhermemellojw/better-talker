package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloco de geração longa: tempos corretos e orçamento dinâmico.
 * Cobre 5/10/15/20/30 min, demo 4/5 e divisão acima de 8192. Puro.
 */
class LongTextRequestTest {

    private fun request(
        kind: LongTextKind,
        minutes: Int,
        scope: LongTextScope = LongTextScope.SECTION,
    ) = LongTextRequest(
        kind = kind,
        scope = scope,
        minutes = minutes,
        sectionMinutes = minutes,
    )

    private fun budget(kind: LongTextKind, minutes: Int) =
        budgetForRequest(request(kind, minutes))

    @Test
    fun discurso5min_meta650Palavras_orcamento2048() {
        val r = request(LongTextKind.SPEECH, 5)
        assertEquals(650, r.targetWords)
        assertEquals(0, r.targetTurns)
        val b = budgetForRequest(r)
        assertEquals(2048, b.maxTokens)
        assertEquals(1, b.parts)
        assertEquals("none", b.reasoningEffort)
    }

    @Test
    fun discurso10min_meta1300Palavras_orcamento2150() {
        val r = request(LongTextKind.SPEECH, 10)
        assertEquals(1300, r.targetWords)
        assertEquals(2150, budgetForRequest(r).maxTokens)
    }

    @Test
    fun discurso15min_meta1950Palavras_orcamento3125() {
        val r = request(LongTextKind.SPEECH, 15)
        assertEquals(1950, r.targetWords)
        assertEquals(3125, budgetForRequest(r).maxTokens)
    }

    @Test
    fun discurso20min_meta2600Palavras_orcamento4100() {
        val r = request(LongTextKind.SPEECH, 20)
        assertEquals(2600, r.targetWords)
        assertEquals(4100, budgetForRequest(r).maxTokens)
    }

    @Test
    fun discurso30min_meta3900Palavras_orcamento6050() {
        val r = request(LongTextKind.SPEECH, 30)
        assertEquals(3900, r.targetWords)
        assertEquals(6050, budgetForRequest(r).maxTokens)
    }

    @Test
    fun demo4min_10Replicas_orcamento2048() {
        val r = request(LongTextKind.DEMO, 4)
        assertEquals(0, r.targetWords)
        assertEquals(10, r.targetTurns)
        assertEquals(2048, budgetForRequest(r).maxTokens)
    }

    @Test
    fun demo5min_12Replicas_orcamento2048() {
        val r = request(LongTextKind.DEMO, 5)
        assertEquals(12, r.targetTurns)
        assertEquals(2048, budgetForRequest(r).maxTokens)
    }

    @Test
    fun demo30min_75Replicas_orcamento6200() {
        val r = request(LongTextKind.DEMO, 30)
        assertEquals(75, r.targetTurns)
        assertEquals(6200, budgetForRequest(r).maxTokens)
    }

    @Test
    fun acimaDe8192_divideEmPartesIguais() {
        // 45 min = 5850 palavras → 8975 tokens brutos → 2 partes de 4488.
        val b45 = budget(LongTextKind.SPEECH, 45)
        assertEquals(8975, b45.rawTokens)
        assertEquals(2, b45.parts)
        assertEquals(4488, b45.maxTokens)

        // 60 min = 7800 palavras → 11900 tokens brutos → 2 partes de 5950.
        val b60 = budget(LongTextKind.SPEECH, 60)
        assertEquals(11900, b60.rawTokens)
        assertEquals(2, b60.parts)
        assertEquals(5950, b60.maxTokens)

        // 90 min = 11700 palavras → 17750 tokens brutos → 3 partes de 5917.
        val b90 = budget(LongTextKind.SPEECH, 90)
        assertEquals(17750, b90.rawTokens)
        assertEquals(3, b90.parts)
        assertEquals(5917, b90.maxTokens)
    }

    @Test
    fun parteDe45_usaOrcamentoDaSecaoE4488() {
        val p1 = LongTextRequest(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            minutes = 23,
            sectionMinutes = 45,
            targetWords = 2990,
            partIndex = 1,
            partCount = 2,
        )
        val b1 = budgetForRequest(p1)
        assertEquals(8975, b1.rawTokens)
        assertEquals(2, b1.parts)
        assertEquals(4488, b1.maxTokens)

        val p2 = p1.copy(minutes = 22, targetWords = 2860, partIndex = 2)
        assertEquals(4488, budgetForRequest(p2).maxTokens)
    }

    @Test
    fun correcaoDeExtensao_permiteOverride8192() {
        val r = LongTextRequest(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            minutes = 23,
            sectionMinutes = 45,
            targetWords = 2990,
            partIndex = 1,
            partCount = 2,
            maxTokensOverride = 8192,
        )
        val b = budgetForRequest(r)
        assertEquals(8192, b.maxTokens)
        assertEquals(2, b.parts)
    }

    @Test
    fun pedidoDe10minDentroDe45_naoUsaOrcamentoDaSecao() {
        val r = LongTextRequest(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            minutes = 10,
            sectionMinutes = 45,
        )
        val b = budgetForRequest(r)
        assertEquals(2150, b.maxTokens)
        assertEquals(1, b.parts)
    }

    @Test
    fun secaoSemPedido_usaSectionMinutes() {
        val resolution = longTextRequestFor(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            requestedMinutes = null,
            sectionMinutes = 7,
        )
        assertTrue(resolution is LongTextResolution.Ready)
        val r = (resolution as LongTextResolution.Ready).request
        assertEquals(7, r.minutes)
        assertEquals(7, r.sectionMinutes)
        assertNull(r.requestedMinutes)
    }

    @Test
    fun pedidoDiferenteDaSecao_pedeConfirmacao() {
        val resolution = longTextRequestFor(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            requestedMinutes = 10,
            sectionMinutes = 5,
        )
        assertTrue(resolution is LongTextResolution.NeedsConfirmation)
        val n = resolution as LongTextResolution.NeedsConfirmation
        assertEquals(5, n.sectionMinutes)
        assertEquals(10, n.requested.minutes)
        assertTrue(n.requested.differsFromSection)
    }

    @Test
    fun pedidoDiferenteConfirmado_viraReady() {
        val resolution = longTextRequestFor(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            requestedMinutes = 10,
            sectionMinutes = 5,
            confirmedDifferentMinutes = true,
        )
        assertTrue(resolution is LongTextResolution.Ready)
        assertEquals(10, (resolution as LongTextResolution.Ready).request.minutes)
    }

    @Test
    fun textoTotal_usaPedidoSemConfirmarSoma() {
        val resolution = longTextRequestFor(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.TOTAL,
            requestedMinutes = 30,
            sectionMinutes = 5,
        )
        assertTrue(resolution is LongTextResolution.Ready)
        val r = (resolution as LongTextResolution.Ready).request
        assertEquals(30, r.minutes)
        assertEquals(5, r.sectionMinutes)
    }

    @Test
    fun semDuracao_retornaNull() {
        assertNull(
            longTextRequestFor(
                kind = LongTextKind.SPEECH,
                scope = LongTextScope.SECTION,
                requestedMinutes = null,
                sectionMinutes = null,
            )
        )
        assertNull(
            longTextRequestFor(
                kind = LongTextKind.SPEECH,
                scope = LongTextScope.SECTION,
                requestedMinutes = 0,
                sectionMinutes = 0,
            )
        )
    }

    @Test
    fun tipoPeloDiscurso_persistido() {
        assertEquals(
            LongTextKind.DEMO,
            longTextKindOf("MINISTRY_PART"),
        )
        assertEquals(
            LongTextKind.DEMO,
            longTextKindOf("FREE_DEMO"),
        )
        assertEquals(
            LongTextKind.SPEECH,
            longTextKindOf("S34_DISCOURSE"),
        )
        assertEquals(
            LongTextKind.SPEECH,
            longTextKindOf(null),
        )
    }

    @Test
    fun minutosPedidosNaMensagem() {
        assertEquals(5, requestedMinutesIn("gere o texto de 5 min"))
        assertEquals(10, requestedMinutesIn("quero 10 minutos desta seção"))
        assertEquals(30, requestedMinutesIn("discurso de 30 min"))
        assertNull(requestedMinutesIn("gere o texto desta seção"))
        assertNull(requestedMinutesIn("5 minutinhos"))
    }

    @Test
    fun avisoDeTempoMantemASecao() {
        val notice = minutesMismatchNotice(sectionMinutes = 5, requestedMinutes = 10)
        assertTrue(notice.contains("esta seção tem 5 min"))
        assertTrue(notice.contains("pediu 10 min"))
        assertTrue(notice.contains("manter 5 min"))
        assertTrue(notice.contains("soma do discurso"))
    }

    // ---------- Verificação de extensão do card ----------

    private fun cardOf(words: Int): String =
        "<texto-do-card><p>" + List(words) { "palavra" }.joinToString(" ") +
            "</p></texto-do-card>"

    private fun demoCardOf(turns: Int): String =
        "<texto-do-card>" + List(turns) { "<p><b>Papel:</b> fala</p>" }.joinToString("") +
            "</texto-do-card>"

    @Test
    fun cardCurtoDeDiscurso_ficaAbaixoDaMeta() {
        val r = request(LongTextKind.SPEECH, 10) // 1300 palavras, piso 1040
        assertTrue(isLongTextUnderTarget(cardOf(500), r))
        assertTrue(isLongTextUnderTarget(cardOf(1039), r))
        assertFalse(isLongTextUnderTarget(cardOf(1040), r))
        assertFalse(isLongTextUnderTarget(cardOf(1300), r))
    }

    @Test
    fun cardCurtoDeDemo_ficaAbaixoDaMeta() {
        val r = request(LongTextKind.DEMO, 4) // 10 réplicas, piso 8
        assertTrue(isLongTextUnderTarget(demoCardOf(7), r))
        assertFalse(isLongTextUnderTarget(demoCardOf(8), r))
        assertFalse(isLongTextUnderTarget(demoCardOf(11), r))
    }

    @Test
    fun respostaSemCard_naoEntraNaVerificacaoDeExtensao() {
        val r = request(LongTextKind.SPEECH, 10)
        assertFalse(isLongTextUnderTarget("Resposta conversacional curta.", r))
    }

    @Test
    fun instrucaoDeExtensao_dizOAtualEOPiso() {
        val r = request(LongTextKind.SPEECH, 10)
        val instruction = longTextExpansionInstruction(cardOf(500), r)
        assertTrue(instruction.contains("500 palavras"))
        assertTrue(instruction.contains("1300 palavras"))
        assertTrue(instruction.contains("1040"))
        assertTrue(instruction.contains("CORREÇÃO DE EXTENSÃO"))
    }

    @Test
    fun segundaExpansao_reenviaOTextoAnterior() {
        val r = request(LongTextKind.SPEECH, 10)
        val instruction = longTextSecondExpansionInstruction(cardOf(500), r)
        assertTrue(instruction.contains("EXPANSÃO DIRIGIDA"))
        assertTrue(instruction.contains("500 palavras"))
        assertTrue(instruction.contains("1040"))
        assertTrue(instruction.contains("TEXTO ANTERIOR"))
    }
}
