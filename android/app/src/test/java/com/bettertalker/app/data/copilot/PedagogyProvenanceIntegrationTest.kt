package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-PEDAGOGY/P4 — integração pedagogia × proveniência persistida do A.
 *
 * Exercita os contratos REAIS que o call site de AI-09 usa: recuperação
 * central do referente, `DeliveryProvenance.rewriteFrom`, o gate central
 * `ContentEligibilityGate`, alvo/hash de card e a separação corpo/subponto.
 * Não cria verificador paralelo, não usa corpus vivo e não chama LLM/Room.
 */
class PedagogyProvenanceIntegrationTest {

    private val sourceText =
        "João 17:17 mostra que a verdade santifica. A aplicação é viver essa verdade."
    private val sourceCorpus = listOf(
        "João 17:17 — Santifica-os por meio da verdade; a tua palavra é a verdade.",
    )
    private val revisedText =
        "João 17:17 mostra que a verdade santifica. A aplicação é praticar essa verdade todos os dias."

    private fun source(
        messageId: String = "msg-source",
        noteId: String = "n1",
        scopeId: String? = "sec-1",
        targetKey: String? = "SECTION:sec-1",
    ): DeliveryProvenance = DeliveryProvenance.newForMessage(
        messageId = messageId,
        turnId = "turn-1",
        noteId = noteId,
        scopeId = scopeId,
        targetKey = targetKey,
        corpus = sourceCorpus,
        questionRefs = emptyList(),
        content = sourceText,
        boundAt = 1_700_000_000_000L,
    )

    private fun rewrite(
        source: DeliveryProvenance = source(),
        text: String = revisedText,
        noteId: String = "n1",
        targetKey: String? = "SECTION:sec-1",
        messageId: String = "msg-rewrite",
    ): DeliveryProvenance? = DeliveryProvenance.rewriteFrom(
        source = source,
        sourceText = sourceText,
        revisedText = text,
        messageId = messageId,
        turnId = "turn-2",
        noteId = noteId,
        scopeId = "sec-1",
        targetKey = targetKey,
        boundAt = 1_700_000_100_000L,
    )

    @Test
    fun ai09_revisaoPreservaProvenienciaAposFimDoTurnoERestart() {
        val source = source()
        // Fim do turno: só o payload persistido importa; nada de corpus vivo.
        val persisted = DeliveryProvenance.fromPayload(
            mapOf("text" to sourceText) + source.toPayload(),
        )
        assertNotNull(persisted)
        assertTrue(persisted!!.binds(sourceText))

        val rewritten = rewrite(persisted)
        assertNotNull(rewritten)
        assertEquals("msg-source", rewritten!!.sourceMessageId)
        assertEquals(source.corpus, rewritten.corpus)
        assertEquals(source.questionRefs, rewritten.questionRefs)
        assertTrue(rewritten.binds(revisedText))
        assertFalse(rewritten.binds(sourceText))
        assertFalse(rewritten.corpus.contains(revisedText))
        assertTrue(rewritten.authorizesCard("n1", "SECTION:sec-1", revisedText))

        // Restart/reabertura: o payload da revisão é a autoridade.
        val reopened = DeliveryProvenance.fromPayload(rewritten.toPayload())
        assertNotNull(reopened)
        assertTrue(reopened!!.authorizesCard("n1", "SECTION:sec-1", revisedText))
        assertFalse(reopened.authorizesCard("n2", "SECTION:sec-1", revisedText))
        assertFalse(reopened.authorizesCard("n1", "SECTION:sec-2", revisedText))
        assertFalse(reopened.authorizesCard("n1", "SECTION:sec-1", revisedText + " editado"))

        val gate = ContentEligibilityGate.forCardInsert(
            revisedText, reopened.corpus, reopened.questionRefs,
        )
        assertTrue(gate.allowed)
        assertEquals(revisedText, gate.text)
    }

    @Test
    fun ai09_provenienciaAusenteOuDeOutraNotaNaoCriaCardElegivel() {
        assertNull(DeliveryProvenance.fromPayload(emptyMap()))
        assertNull(rewrite(noteId = "n2"))
        assertNull(DeliveryProvenance.rewriteFrom(
            source = source(),
            sourceText = sourceText + " adulterado",
            revisedText = revisedText,
            messageId = "msg-rewrite",
            turnId = "turn-2",
            noteId = "n1",
            scopeId = "sec-1",
            targetKey = "SECTION:sec-1",
            boundAt = 1L,
        ))

        val source = source()
        assertNull(provenanceForMessage(mapOf("outra" to source), "outra", "n1"))
        assertFalse(source.authorizesCard("n2", "SECTION:sec-1", sourceText))
        assertFalse(
            ContentEligibilityGate.forCardInsert(revisedText, emptyList(), emptyList()).allowed
        )
    }

    @Test
    fun ai09_referenciaNovaNaoEhAutorizadaPelaFonte() {
        val rewritten = rewrite()!!
        val withNewRef =
            "$revisedText Mateus 24:14 confirma que a obra continua."
        val gate = ContentEligibilityGate.forCardInsert(
            withNewRef, rewritten.corpus, rewritten.questionRefs,
        )
        assertFalse(gate.allowed)
        assertTrue(gate.findings.any { it == FidelityFinding.REFERENCE_UNSUPPORTED })
    }

    @Test
    fun ai09_referenteAmbiguoNaoEscolheCardAntigo() {
        val now = 1_700_000_200_000L
        fun message(id: String) = ConversationalReferentRecovery.Message(
            id = id,
            noteId = "n1",
            sectionId = "sec-1",
            payload = CardPayload(
                targetKind = CardTargetKind.SECTION,
                targetId = "sec-1",
                targetLabel = "Ponto 1",
                status = CardStatus.AVAILABLE,
                suggestedAction = CardSuggestedAction.REPLACE,
            ).toMap() + (
                "text" to "<texto-do-card>$sourceText</texto-do-card>"
            ),
            createdAt = now,
        )

        val outcome = ConversationalReferentRecovery.recover(
            messages = listOf(message("msg-a"), message("msg-b")),
            context = ConversationalReferentRecovery.Context(
                noteId = "n1",
                sectionId = "sec-1",
                targets = mapOf(
                    "SECTION:sec-1" to ConversationalReferentRecovery.TargetState(
                        exists = true,
                        currentHash = hashText("corpo atual"),
                    ),
                ),
            ),
        )
        assertTrue(outcome is ConversationalReferentRecovery.Outcome.Ambiguous)
    }

    @Test
    fun ai09_corpoSubstituivelNaoDuplicaSubpontos() {
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
                    developedText = "Prosa do subponto, que não é alvo do card.",
                ),
            ),
        )
        val revised = section.copy(bodyText = "Corpo revisado.")
        assertEquals("Corpo original.", pointReviewDraftText(section))
        assertEquals("Corpo revisado.", pointReviewDraftText(revised))
        assertEquals(
            section.subPoints.map { it.developedText },
            revised.subPoints.map { it.developedText },
        )
        assertFalse(pointReviewDraftText(revised).contains("Prosa do subponto"))
    }

    @Test
    fun ai09_hashGateBloqueiaReplaceSemConsentimentoOuAposEdicao() {
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
            currentTargetHash = hashText("Corpo editado fora do card."),
        )
        assertEquals(CardStatus.STALE, stale)
        assertFalse(canReplaceCard(stale, hashMatches = false))
    }

    @Test
    fun ai09_payloadReatribuidoAOutraMensagemEhRejeitado() {
        val source = source()
        // O mapa persistido é indexado pelo id da mensagem dona; um vínculo
        // copiado para outro id não é aceito.
        assertNull(provenanceForMessage(mapOf("msg-outra" to source), "msg-outra", "n1"))
        assertNotNull(provenanceForMessage(mapOf("msg-source" to source), "msg-source", "n1"))
    }
}
