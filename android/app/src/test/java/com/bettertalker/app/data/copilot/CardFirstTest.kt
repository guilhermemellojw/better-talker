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
            suggestedAction = CardSuggestedAction.REPLACE,
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
        assertEquals(CardSuggestedAction.REPLACE, back.suggestedAction)
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
    fun userPickCapturaHashNoMomentoEMostraAviso() {
        val noTarget = payload().copy(
            targetKind = null,
            targetId = null,
            targetLabel = null,
            baseHash = null,
            capturedFrom = null,
            capturedAt = null,
            status = CardStatus.NO_TARGET,
        )
        val picked = applyUserPick(
            card = noTarget,
            sectionId = "sec-9",
            label = "Ponto 9",
            contentHtml = "<p>conteúdo atual</p>",
            now = 9_000L,
        )
        assertEquals(CardTargetKind.SECTION, picked.targetKind)
        assertEquals("sec-9", picked.targetId)
        assertEquals("Ponto 9", picked.targetLabel)
        assertEquals(
            com.bettertalker.app.data.edit.hashText("<p>conteúdo atual</p>"),
            picked.baseHash,
        )
        assertEquals(CardCapturedFrom.USER_PICK, picked.capturedFrom)
        assertEquals(9_000L, picked.capturedAt)
        assertEquals(CardStatus.AVAILABLE, picked.status)
    }

    @Test
    fun alvoRemovidoViraTargetMissingESoDispensa() {
        val status = resolveCardStatus(
            payload = payload(),
            targetExists = false,
            currentTargetHash = null,
        )
        assertEquals(CardStatus.TARGET_MISSING, status)
        assertFalse(canInsertCard(status))
        assertFalse(canReplaceCard(status, hashMatches = true))
        assertFalse(canGateCard(status, isLastCopilotMessage = true, applied = false))
        assertEquals("Destino não existe mais", cardStatusLabel(status))
    }

    @Test
    fun failedNaoPermiteInsertNemGate() {
        val status = CardStatus.FAILED
        assertFalse(canInsertCard(status))
        assertFalse(canReplaceCard(status, hashMatches = true))
        assertFalse(canGateCard(status, isLastCopilotMessage = true, applied = false))
        assertEquals("Falha na ação", cardStatusLabel(status))
    }

    @Test
    fun matrizDeAcoesPorEstado() {
        val insertable = listOf(
            CardStatus.AVAILABLE,
            CardStatus.STALE,
            CardStatus.SUPERSEDED,
        )
        val blocked = listOf(
            CardStatus.APPLIED,
            CardStatus.NO_TARGET,
            CardStatus.TARGET_MISSING,
            CardStatus.DISMISSED,
            CardStatus.FAILED,
        )
        insertable.forEach { assertTrue("insert $it", canInsertCard(it)) }
        blocked.forEach { assertFalse("insert $it", canInsertCard(it)) }

        assertTrue(canReplaceCard(CardStatus.AVAILABLE, hashMatches = true))
        assertTrue(canReplaceCard(CardStatus.SUPERSEDED, hashMatches = true))
        assertFalse(canReplaceCard(CardStatus.STALE, hashMatches = true))
        blocked.forEach { assertFalse("replace $it", canReplaceCard(it, hashMatches = true)) }

        val gateYes = listOf(CardStatus.AVAILABLE, CardStatus.STALE)
        val gateNo = blocked + CardStatus.SUPERSEDED
        gateYes.forEach {
            assertTrue("gate $it", canGateCard(it, isLastCopilotMessage = true, applied = false))
        }
        gateNo.forEach {
            assertFalse("gate $it", canGateCard(it, isLastCopilotMessage = true, applied = false))
        }
    }

    @Test
    fun semAlvoNaoAceitaGate() {
        val status = CardStatus.NO_TARGET
        assertFalse(canGateCard(status, isLastCopilotMessage = true, applied = false))
        assertFalse(canInsertCard(status))
    }

    @Test
    fun cardSemAlvoNaoEntraNoSuperseded() {
        val noTarget = payload().copy(targetKind = null, targetId = null)
        val withTarget = payload().copy(targetId = "sec-1")
        val flags = supersededCardMessageIds(
            listOf("no-target" to noTarget, "target" to withTarget),
        )
        assertTrue("no-target" !in flags)
        assertTrue("target" !in flags)
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
        assertEquals(
            "Conversa antes.\n<texto-do-card>Novo</texto-do-card>\nConversa depois.",
            replaceCardTextBlock(raw, "Novo</texto-do-card>"),
        )
        assertNull(replaceCardTextBlock("sem card", "x"))
    }

    @Test
    fun cardAntigoSemSugestaoLeComoAppend() {
        val p = payload()
        val map = p.toMap().toMutableMap().apply { remove("cardSuggestedAction") }
        val back = CardPayload.fromMap(map)!!
        assertEquals(CardSuggestedAction.APPEND, back.suggestedAction)
    }

    @Test
    fun origemNaoSeConfundeComTurno() {
        val p = payload().copy(turnId = "turn-9", origin = CardOrigin.TRANSFORM)
        val map = p.toMap()
        assertEquals("turn-9", map["cardTurnId"])
        assertEquals("TRANSFORM", map["cardOrigin"])
        assertFalse(map.containsKey("cardSource"))
    }

    @Test
    fun tresCardsMesmoAlvoDoisSupersededUmAvailable() {
        val cards = listOf(payload(), payload(), payload())
        val ids = supersededCardMessageIds(
            listOf("a" to cards[0], "b" to cards[1], "c" to cards[2]),
        )
        assertEquals(setOf("a", "b"), ids)
        val statuses = cards.mapIndexed { index, card ->
            resolveCardStatus(
                card,
                targetExists = true,
                currentTargetHash = card.baseHash,
                superseded = when (index) {
                    0, 1 -> true
                    else -> false
                },
            )
        }
        assertEquals(
            listOf(CardStatus.SUPERSEDED, CardStatus.SUPERSEDED, CardStatus.AVAILABLE),
            statuses,
        )
    }

    @Test
    fun cardsEmAlvosDiferentesNaoSeSupersedem() {
        val a = payload().copy(targetId = "sec-1")
        val b = payload().copy(targetId = "sec-2")
        val c = payload().copy(targetId = "sec-3")
        assertTrue(supersededCardMessageIds(listOf("a" to a, "b" to b, "c" to c)).isEmpty())
    }

    @Test
    fun cardAppliedNaoViraSuperseded() {
        val applied = payload(CardStatus.APPLIED)
        assertEquals(
            CardStatus.APPLIED,
            resolveCardStatus(
                applied,
                targetExists = true,
                currentTargetHash = applied.baseHash,
                superseded = true,
            ),
        )
    }
}
