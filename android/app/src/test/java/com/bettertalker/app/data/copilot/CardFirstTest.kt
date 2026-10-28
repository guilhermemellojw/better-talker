package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card-first fase 1 — alvo, payload, estados, stale, gate e read-only. */
class CardFirstTest {

    private fun target(
        from: CardCapturedFrom = CardCapturedFrom.ACTIVE_BLOCK,
        focus: String? = null,
    ) = cardTargetForSection(
        sectionId = "sec-1",
        label = "Ponto 1",
        contentHtml = "<p>base</p>",
        capturedFrom = from,
        capturedAt = 1_000L,
        focusLabel = focus,
    )

    private fun payload(
        status: CardStatus = CardStatus.AVAILABLE,
        target: CardTarget? = target(),
    ) = CardPayload(
        targetKind = target?.kind,
        targetId = target?.id,
        targetLabel = target?.label,
        baseHash = target?.baseHash,
        capturedFrom = target?.capturedFrom,
        capturedAt = target?.capturedAt,
        turnId = "turn-1",
        summary = "Resumo",
        gateMessageId = "msg-1",
        status = status,
    )

    @Test
    fun payloadFazRoundTripCompleto() {
        val p = payload().copy(
            origin = CardOrigin.TRANSFORM,
            appliedAt = 2_000L,
        )
        val back = CardPayload.fromMap(p.toMap())!!
        assertEquals(1, back.schema)
        assertEquals(CardTargetKind.SECTION, back.targetKind)
        assertEquals("sec-1", back.targetId)
        assertEquals("Ponto 1", back.targetLabel)
        assertEquals(p.baseHash, back.baseHash)
        assertEquals(CardCapturedFrom.ACTIVE_BLOCK, back.capturedFrom)
        assertEquals(1_000L, back.capturedAt)
        assertEquals("turn-1", back.turnId)
        assertEquals(CardOrigin.TRANSFORM, back.origin)
        assertEquals("Resumo", back.summary)
        assertEquals("msg-1", back.gateMessageId)
        assertEquals(CardStatus.AVAILABLE, back.status)
        assertEquals(2_000L, back.appliedAt)
    }

    @Test
    fun summaryTemTetoDe160() {
        val long = "a".repeat(300)
        assertEquals(160, capCardSummary(long).length)
        assertTrue(capCardSummary(long).endsWith("…"))
    }

    @Test
    fun transformCapturaAlvoComOrigemTransform() {
        val t = target(from = CardCapturedFrom.TRANSFORM, focus = "Ponto 2")
        assertEquals(CardCapturedFrom.TRANSFORM, t.capturedFrom)
        assertEquals(CardTargetKind.SECTION, t.kind)
        assertEquals("sec-1", t.id)
        assertTrue(t.displayLabel.contains("Para: Ponto 2"))
    }

    @Test
    fun alvoNotaUsaRichHtmlEKindNote() {
        val t = cardTargetForNote(
            noteId = "note-1",
            label = "Discurso",
            richHtml = "<p>corpo</p>",
            capturedFrom = CardCapturedFrom.WHOLE,
            capturedAt = 1_000L,
        )
        assertEquals(CardTargetKind.NOTE, t.kind)
        assertEquals("note-1", t.id)
        assertEquals(com.bettertalker.app.data.edit.hashText("<p>corpo</p>"), t.baseHash)
    }

    @Test
    fun focoEmSubpontoApontaParaSecaoComRotulo() {
        val t = target(from = CardCapturedFrom.ACTIVE_BLOCK, focus = "Ponto 2")
        assertEquals(CardTargetKind.SECTION, t.kind)
        assertEquals("sec-1", t.id)
        assertTrue(t.displayLabel.contains("Ponto 1"))
        assertTrue(t.displayLabel.contains("Para: Ponto 2"))
    }

    @Test
    fun semAlvoViraNoTargetEExigeUserPick() {
        val status = resolveCardStatus(
            payload = payload().copy(targetKind = null, targetId = null, baseHash = null),
            targetExists = false,
            currentTargetHash = null,
        )
        assertEquals(CardStatus.NO_TARGET, status)
        assertFalse(canInsertCard(status))
        assertTrue(canInsertCard(CardStatus.AVAILABLE))
    }

    @Test
    fun hashDiferenteViraStaleEStaleSoPermiteAppend() {
        val p = payload()
        val status = resolveCardStatus(
            payload = p,
            targetExists = true,
            currentTargetHash = p.baseHash + "x",
        )
        assertEquals(CardStatus.STALE, status)
        assertTrue(canInsertCard(status))
        assertFalse(canReplaceCard(status, hashMatches = true))
        assertFalse(canReplaceCard(status, hashMatches = false))
    }

    @Test
    fun supersededPermiteInsertEReplaceSeHashConfere() {
        val p = payload()
        val status = resolveCardStatus(
            payload = p,
            targetExists = true,
            currentTargetHash = p.baseHash,
            superseded = true,
        )
        assertEquals(CardStatus.SUPERSEDED, status)
        assertTrue(canInsertCard(status))
        assertTrue(canReplaceCard(status, hashMatches = true))
        assertFalse(canReplaceCard(status, hashMatches = false))
    }

    @Test
    fun gateSoAceitaCardMaisRecenteEUltimaFala() {
        assertTrue(canGateCard(CardStatus.AVAILABLE, isLastCopilotMessage = true, applied = false))
        assertTrue(canGateCard(CardStatus.STALE, isLastCopilotMessage = true, applied = false))
        assertFalse(canGateCard(CardStatus.AVAILABLE, isLastCopilotMessage = false, applied = false))
        assertFalse(canGateCard(CardStatus.AVAILABLE, isLastCopilotMessage = true, applied = true))
        assertFalse(canGateCard(CardStatus.SUPERSEDED, isLastCopilotMessage = true, applied = false))
    }

    @Test
    fun cancelledEErrorSaoReadOnlyDoTurnoNaoDoCard() {
        assertTrue(cardReadOnly(TurnPhase.CANCELLED))
        assertTrue(cardReadOnly(TurnPhase.ERROR))
        assertFalse(cardReadOnly(TurnPhase.DONE))
        assertFalse(cardActionsEnabled(TurnPhase.CANCELLED, CardStatus.AVAILABLE))
        assertFalse(cardActionsEnabled(TurnPhase.ERROR, CardStatus.AVAILABLE))
        assertTrue(cardActionsEnabled(TurnPhase.DONE, CardStatus.AVAILABLE))
    }

    @Test
    fun extractCardTextPegaSoOPrimeiroBloco() {
        val text = "Conversa antes.\n<texto-do-card>Texto aplicável</texto-do-card>\n" +
            "Conversa depois.\n<texto-do-card>Segundo</texto-do-card>"
        assertEquals("Texto aplicável", extractCardText(text))
        assertNull(extractCardText("sem marcador"))
        assertNull(extractCardText("<texto-do-card>aberto sem fechar"))
    }

    @Test
    fun appliedDismissedFailedNaoSaoRecalculadosPorHash() {
        listOf(CardStatus.APPLIED, CardStatus.DISMISSED, CardStatus.FAILED).forEach { status ->
            val out = resolveCardStatus(
                payload = payload(status),
                targetExists = false,
                currentTargetHash = "outro",
            )
            assertEquals(status, out)
        }
        assertEquals(
            CardStatus.TARGET_MISSING,
            resolveCardStatus(payload(), targetExists = false, currentTargetHash = null),
        )
    }

    @Test
    fun cardMaisNovoNoMesmoAlvoMarcaOsAnterioresComoSuperseded() {
        val a = payload().copy(targetId = "sec-1")
        val b = payload().copy(targetId = "sec-1")
        val c = payload().copy(targetId = "sec-2")
        val flags = supersededCardMessageIds(
            listOf("msg-a" to a, "msg-b" to b, "msg-c" to c),
        )
        assertEquals(setOf("msg-a"), flags)
        assertTrue("msg-b" !in flags)
        assertTrue("msg-c" !in flags)
    }

    @Test
    fun revisaoTrocaSoOBlocoDoCardEPreservaConversa() {
        val raw = "Conversa antes.\n<texto-do-card>Antigo</texto-do-card>\nConversa depois."
        val updated = replaceCardTextBlock(raw, "Novo texto")!!
        assertEquals(
            "Conversa antes.\n<texto-do-card>Novo texto</texto-do-card>\nConversa depois.",
            updated,
        )
        assertNull(replaceCardTextBlock("sem card", "x"))
    }

    @Test
    fun origemNaoSeConfundeComTurno() {
        val p = payload().copy(turnId = "turn-9", origin = CardOrigin.TRANSFORM)
        val map = p.toMap()
        assertEquals("turn-9", map["cardTurnId"])
        assertEquals("TRANSFORM", map["cardOrigin"])
        assertFalse(map.containsKey("cardSource"))
    }
}
