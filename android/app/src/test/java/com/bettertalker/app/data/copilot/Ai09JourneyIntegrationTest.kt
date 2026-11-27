package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-PEDAGOGY/P4.1 — jornadas reais do AI-09 (sem Room/UI/LLM).
 *
 * Exercita os mesmos componentes usados pelo aplicativo: recuperação do
 * referente, handoff persistido de proveniência (`RewriteHandoff`, extraído do
 * próprio `post`), contrato central de elegibilidade de A e gate de card.
 */
class Ai09JourneyIntegrationTest {

    private val sourceText =
        "João 17:17 mostra que a verdade santifica. A aplicação é viver essa verdade."
    private val sourceCorpus = listOf(
        "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade.",
    )
    private val revisedText =
        "João 17:17 mostra que a verdade santifica. A aplicação é praticar essa verdade todos os dias."

    private fun source() = DeliveryProvenance.newForMessage(
        messageId = "msg-source",
        turnId = "turn-1",
        noteId = "n1",
        scopeId = "sec-1",
        targetKey = "SECTION:sec-1",
        corpus = sourceCorpus,
        questionRefs = emptyList(),
        content = sourceText,
        boundAt = 1_700_000_000_000L,
    )

    private fun handoff(
        source: DeliveryProvenance? = source(),
        sourceTextValue: String? = sourceText,
        text: String = revisedText,
        noteId: String = "n1",
        scopeId: String? = "sec-1",
        targetKey: String? = "SECTION:sec-1",
        currentTurnCorpus: List<String> = emptyList(),
    ) = RewriteHandoff.decide(
        RewriteHandoff.Input(
            fromMe = false,
            source = source,
            sourceText = sourceTextValue,
            revisedText = text,
            messageId = "msg-rewrite",
            turnId = "turn-2",
            noteId = noteId,
            scopeId = scopeId,
            targetKey = targetKey,
            boundAt = 1_700_000_100_000L,
            currentTurnCorpus = currentTurnCorpus,
        )
    )

    @Test
    fun ai09_provenanceSourceAusenteEhRejeitadaSemFallback() {
        val out = handoff(source = null, sourceTextValue = sourceText)
        assertTrue(out is RewriteHandoff.Outcome.Rejected)
        assertEquals("REVISION_WITHOUT_SOURCE", (out as RewriteHandoff.Outcome.Rejected).reason)
        // O corpus do turno corrente não cria proveniência para a revisão.
        assertTrue(handoff(
            source = null,
            sourceTextValue = sourceText,
            currentTurnCorpus = sourceCorpus,
        ) is RewriteHandoff.Outcome.Rejected)
    }

    @Test
    fun ai09_provenanceSourceTextAusenteEhRejeitada() {
        val out = handoff(source = source(), sourceTextValue = null)
        assertTrue(out is RewriteHandoff.Outcome.Rejected)
        assertEquals("REVISION_WITHOUT_SOURCE", (out as RewriteHandoff.Outcome.Rejected).reason)
        val blank = handoff(source = source(), sourceTextValue = "   ")
        assertTrue(blank is RewriteHandoff.Outcome.Rejected)
    }

    @Test
    fun ai09_corpusDoTurnoAtualNaoEhFallbackDaRevisao() {
        val currentTurnCorpus = listOf("João 3:16 — conteúdo gerado no turno corrente.")
        val out = handoff(currentTurnCorpus = currentTurnCorpus)
        assertTrue(out is RewriteHandoff.Outcome.Accepted)
        val bound = (out as RewriteHandoff.Outcome.Accepted).provenance
        assertEquals(sourceCorpus, bound.corpus)
        assertFalse(bound.corpus.any { it in currentTurnCorpus })
        assertFalse(bound.corpus.contains(revisedText))
    }

    @Test
    fun ai09_textoRevisadoNuncaViraFonteDeSiMesmo() {
        val out = handoff()
        assertTrue(out is RewriteHandoff.Outcome.Accepted)
        val bound = (out as RewriteHandoff.Outcome.Accepted).provenance
        assertTrue(bound.binds(revisedText))
        assertFalse(bound.binds(sourceText))
        assertFalse(bound.corpus.any { it.contains("praticar essa verdade todos os dias") })
        assertEquals("msg-source", bound.sourceMessageId)
    }

    @Test
    fun ai09_notaEscopoOuAlvoDivergentesRejeitados() {
        assertTrue(handoff(noteId = "n2") is RewriteHandoff.Outcome.Rejected)
        assertTrue(handoff(scopeId = "sec-2") is RewriteHandoff.Outcome.Rejected)
        assertTrue(handoff(targetKey = "SECTION:sec-2") is RewriteHandoff.Outcome.Rejected)
        assertTrue(handoff() is RewriteHandoff.Outcome.Accepted)
    }

    @Test
    fun ai09_jornadaRecuperaReferentePersisteReabreEInsere() {
        val base = source()
        val card = CardPayload(
            targetKind = CardTargetKind.SECTION,
            targetId = "sec-1",
            targetLabel = "Ponto 1",
            status = CardStatus.AVAILABLE,
            suggestedAction = CardSuggestedAction.REPLACE,
            gateMessageId = "msg-source",
        )
        val message = ConversationalReferentRecovery.Message(
            id = "msg-source",
            noteId = "n1",
            sectionId = "sec-1",
            payload = card.toMap() + base.toPayload() +
                ("text" to "<texto-do-card>$sourceText</texto-do-card>"),
            createdAt = 1_700_000_050_000L,
        )
        val recovered = ConversationalReferentRecovery.recover(
            messages = listOf(message),
            context = ConversationalReferentRecovery.Context(
                noteId = "n1",
                sectionId = "sec-1",
                targets = mapOf("SECTION:sec-1" to ConversationalReferentRecovery.TargetState(
                    exists = true, currentHash = hashText("corpo original"),
                )),
            ),
        )
        assertTrue(recovered is ConversationalReferentRecovery.Outcome.Found)
        val found = recovered as ConversationalReferentRecovery.Outcome.Found
        assertEquals("msg-source", found.messageId)
        assertEquals("SECTION:sec-1", cardTargetKey(found.card))

        // Restart: o payload persistido é a única autoridade.
        val reopened = DeliveryProvenance.fromPayload(
            mapOf("text" to sourceText) + base.toPayload(),
        )
        assertNotNull(reopened)
        val out = handoff(
            source = reopened,
            targetKey = cardTargetKey(found.card),
        )
        assertTrue(out is RewriteHandoff.Outcome.Accepted)
        val bound = (out as RewriteHandoff.Outcome.Accepted).provenance
        val stored = DeliveryProvenance.fromPayload(bound.toPayload())
        assertNotNull(stored)
        assertTrue(stored!!.authorizesCard("n1", "SECTION:sec-1", revisedText))
        assertTrue(ContentEligibilityGate.forCardInsert(
            revisedText, stored.corpus, stored.questionRefs,
        ).allowed)
        assertTrue(ContentEligibilityGate.forTopicInsert(
            revisedText, stored.corpus, stored.questionRefs,
        ).allowed)
    }

    @Test
    fun ai09_consentimentoManualEFalhaFechadaNoReplace() {
        val target = cardTargetForSection(
            sectionId = "sec-1",
            label = "Ponto 1",
            contentHtml = "Corpo original.",
            capturedFrom = CardCapturedFrom.ACTIVE_BLOCK,
            capturedAt = 1L,
        )
        val card = CardPayload(
            targetKind = target.kind,
            targetId = target.id,
            targetLabel = target.displayLabel,
            baseHash = target.baseHash,
            capturedFrom = target.capturedFrom,
            capturedAt = target.capturedAt,
            status = CardStatus.AVAILABLE,
            suggestedAction = CardSuggestedAction.REPLACE,
        )
        val stale = resolveCardStatus(
            payload = card,
            targetExists = true,
            currentTargetHash = hashText("Corpo editado sem consentimento."),
        )
        assertEquals(CardStatus.STALE, stale)
        assertFalse(canReplaceCard(stale, hashMatches = false))
        assertFalse(canReplaceCard(stale, hashMatches = true))
    }

    @Test
    fun ai09_corpoSubpontoEAmbiguoPreservados() {
        val section = NoteSectionSnapshot(
            id = "sec-1",
            order = 1,
            role = SectionRole.BODY,
            title = "Ponto 1",
            minutes = 4,
            bodyText = "Corpo original.",
            subPoints = listOf(
                NoteSubPointSnapshot(
                    id = "sub-1",
                    order = 0,
                    outlineText = "Sub-ponto 1",
                    developedText = "Prosa do subponto, não alvo do card.",
                ),
            ),
        )
        val revised = section.copy(bodyText = "Corpo revisado.")
        assertEquals("Corpo revisado.", pointReviewDraftText(revised))
        assertEquals(
            section.subPoints.map { it.developedText },
            revised.subPoints.map { it.developedText },
        )
        assertFalse(pointReviewDraftText(revised).contains("Prosa do subponto"))

        val now = 1_700_000_200_000L
        fun ambiguous(id: String) = ConversationalReferentRecovery.Message(
            id = id,
            noteId = "n1",
            sectionId = "sec-1",
            payload = CardPayload(
                targetKind = CardTargetKind.SECTION,
                targetId = "sec-1",
                targetLabel = "Ponto 1",
                status = CardStatus.AVAILABLE,
            ).toMap() + ("text" to "<texto-do-card>$sourceText</texto-do-card>"),
            createdAt = now,
        )
        assertTrue(ConversationalReferentRecovery.recover(
            messages = listOf(ambiguous("a"), ambiguous("b")),
            context = ConversationalReferentRecovery.Context(
                noteId = "n1",
                sectionId = "sec-1",
                targets = mapOf("SECTION:sec-1" to ConversationalReferentRecovery.TargetState(
                    exists = true, currentHash = hashText("corpo"),
                )),
            ),
        ) is ConversationalReferentRecovery.Outcome.Ambiguous)
    }

    @Test
    fun ai03Ai04_corpusDoPontoAnteriorContinuaAutorizandoGate() {
        val allowed = ContentEligibilityGate.forDraftReview(
            revisedText, sourceCorpus, listOf("João 17:17"),
        )
        assertTrue(allowed.allowed)
        assertEquals(revisedText, allowed.text)
        val unrelated = ContentEligibilityGate.forDraftReview(
            revisedText, listOf("João 3:16 — outro texto."), emptyList(),
        )
        assertFalse(unrelated.allowed)
    }
}
