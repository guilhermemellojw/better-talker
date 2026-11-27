package com.bettertalker.app.data.copilot

import com.bettertalker.app.S34Fixture
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34StructuralRetrieval
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BETA-PEDAGOGY/P3 — integração pedagogia × confiabilidade.
 *
 * Testes puros: reutilizam o contrato de card, a recuperação central de
 * referente e o corpus documental existente. Não criam verificador novo,
 * não chamam LLM/Room e não ligam a proveniência de A antes do contrato.
 */
class PedagogyFidelityIntegrationTest {

    private fun section(
        body: String,
        subs: List<String> = emptyList(),
    ) = NoteSectionSnapshot(
        id = "sec-1",
        order = 1,
        role = SectionRole.BODY,
        title = "Ponto 1",
        minutes = 4,
        bodyText = body,
        subPoints = subs.mapIndexed { index, text ->
            NoteSubPointSnapshot(
                id = "sub-$index",
                order = index,
                outlineText = "Sub-ponto ${index + 1}",
                developedText = text,
            )
        },
    )

    private fun recover(
        section: NoteSectionSnapshot,
        messages: List<ConversationalReferentRecovery.Message>,
    ) = ConversationalReferentRecovery.recover(
        messages = messages,
        context = ConversationalReferentRecovery.Context(
            noteId = "n1",
            sectionId = section.id,
            targets = mapOf(
                "SECTION:${section.id}" to
                    ConversationalReferentRecovery.TargetState(
                        exists = true,
                        currentHash = hashText(section.bodyText),
                    ),
            ),
        ),
    )

    /**
     * Espelha as condições do applyCard real: status inserível + REPLACE só
     * com hash atual igual ao baseHash. Subpontos são registros separados.
     */
    private fun applyBodyReplace(
        section: NoteSectionSnapshot,
        card: CardPayload,
        revisedText: String,
        currentHash: String,
    ): NoteSectionSnapshot? {
        val status = resolveCardStatus(
            payload = card,
            targetExists = true,
            currentTargetHash = currentHash,
        )
        if (!canInsertCard(status)) return null
        if (!canReplaceCard(status, hashMatches = currentHash == card.baseHash)) {
            return null
        }
        return section.copy(bodyText = revisedText)
    }

    @Test
    fun ai09_cardAplicadoAposTurnoERestartPreservaSubpontos() {
        val original = section(
            body = "Corpo original.",
            subs = listOf("Primeiro subponto", "Segundo subponto"),
        )
        val revised = "Corpo revisado."
        val target = cardTargetForSection(
            sectionId = original.id,
            label = original.title,
            contentHtml = original.bodyText,
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
            turnId = "turn-1",
            origin = CardOrigin.TRANSFORM,
            summary = capCardSummary(revised),
            gateMessageId = "msg-1",
            status = CardStatus.AVAILABLE,
            suggestedAction = CardSuggestedAction.REPLACE,
        )
        val message = ConversationalReferentRecovery.Message(
            id = "msg-1",
            noteId = "n1",
            sectionId = original.id,
            payload = card.toMap() +
                ("text" to "<texto-do-card>$revised</texto-do-card>"),
            createdAt = 100L,
        )

        // Fim do turno: só a mensagem foi persistida; nada foi aplicado.
        val afterTurn = recover(original, listOf(message))
            as ConversationalReferentRecovery.Outcome.Found
        assertEquals(CardStatus.AVAILABLE, afterTurn.status)

        // Restart: reconstrói seção e card a partir do estado persistido.
        val restarted = section(
            body = original.bodyText,
            subs = original.subPoints.map { it.developedText },
        )
        val restartedCard = CardPayload.fromMap(afterTurn.card.toMap())!!
        val afterRestart = recover(restarted, listOf(message))
            as ConversationalReferentRecovery.Outcome.Found
        assertEquals(restartedCard.baseHash, afterRestart.card.baseHash)

        val applied = applyBodyReplace(
            section = restarted,
            card = afterRestart.card,
            revisedText = afterRestart.text!!,
            currentHash = hashText(restarted.bodyText),
        )!!
        assertEquals(revised, pointReviewDraftText(applied))
        assertEquals(
            original.subPoints.map { it.developedText },
            applied.subPoints.map { it.developedText },
        )
        assertFalse(applied.bodyText.contains("Primeiro subponto"))
        assertFalse(pointReviewDraftText(applied).contains("Segundo subponto"))

        // O mesmo card não pode ser reaplicado depois que o corpo mudou.
        assertNull(
            applyBodyReplace(
                section = applied,
                card = afterRestart.card,
                revisedText = "Outra revisão.",
                currentHash = hashText(applied.bodyText),
            )
        )
    }

    @Test
    fun ai09_hashGateBloqueiaReplaceDepoisDeEdicaoExterna() {
        val original = section("Corpo original.", listOf("Subponto A"))
        val target = cardTargetForSection(
            sectionId = original.id,
            label = original.title,
            contentHtml = original.bodyText,
            capturedFrom = CardCapturedFrom.ACTIVE_BLOCK,
            capturedAt = 1L,
        )
        val card = CardPayload(
            targetKind = target.kind,
            targetId = target.id,
            baseHash = target.baseHash,
            status = CardStatus.AVAILABLE,
        )
        val editedHash = hashText("Corpo editado fora do card.")
        val status = resolveCardStatus(
            payload = card,
            targetExists = true,
            currentTargetHash = editedHash,
        )
        assertEquals(CardStatus.STALE, status)
        assertFalse(canReplaceCard(status, hashMatches = editedHash == card.baseHash))
        assertNull(
            applyBodyReplace(
                section = original,
                card = card,
                revisedText = "Corpo revisado.",
                currentHash = editedHash,
            )
        )
        assertEquals(listOf("Subponto A"), original.subPoints.map { it.developedText })
    }

    @Test
    fun ai03_refsDoPontoAnteriorRelacionadoEntramNoCorpusDocumental() {
        val doc = S34Parser.parseS34(S34Fixture.TEXT)
        val view = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
        val ready = OratoryGeneration.spec(
            mode = OratoryGeneration.Mode.TRANSITION,
            document = doc,
            view = view,
            action = OratoryGeneration.Action.INSERT,
            referenceTexts = mapOf(
                "Leia João 17:17." to "Texto autorizado de João 17:17.",
                "Leia Hebreus 10:23." to "Texto autorizado de Hebreus 10:23.",
            ),
            relatedSectionId = "sec-1",
        ) as OratoryGeneration.Result.Ready

        val corpus = OratoryGeneration.oruaryDocumentarySources(
            dossier = null,
            spec = ready.spec,
            resolvedSectionId = "sec-2",
        )
        assertTrue(corpus.any { it.reference.contains("João 17:17") })
        assertTrue(corpus.any { it.reference.contains("Hebreus 10:23") })
    }

    @Test
    fun ai04_refsDoPontoRelacionadoEntramNoCorpusDocumental() {
        val doc = S34Parser.parseS34(S34Fixture.TEXT)
        val view = S34StructuralRetrieval.scopeToSection(doc, "sec-1")!!
        val ready = OratoryGeneration.spec(
            mode = OratoryGeneration.Mode.DEVELOPMENT,
            document = doc,
            view = view,
            action = OratoryGeneration.Action.INSERT,
            referenceTexts = mapOf(
                "Leia João 17:17." to "Texto autorizado de João 17:17.",
                "Leia Hebreus 10:23." to "Texto autorizado de Hebreus 10:23.",
            ),
            relatedSectionId = "sec-3",
        ) as OratoryGeneration.Result.Ready

        val corpus = OratoryGeneration.oruaryDocumentarySources(
            dossier = null,
            spec = ready.spec,
            resolvedSectionId = "sec-1",
        )
        assertTrue(corpus.any { it.reference.contains("João 17:17") })
        assertTrue(corpus.any { it.reference.contains("Hebreus 10:23") })
    }

    @Test
    fun ai09_textoRevisadoNaoViraFonteDeReferenciaNova() {
        val prompts = PointReviewRewritePrompts(
            system = "",
            user = "",
            originalText = "Corpo com João 17:17.",
            originalWords = 5,
            reductionPercent = null,
            targetWords = 5,
            minWords = 1,
            maxWords = 5,
            requiredRefs = emptyList(),
            allowedRefs = emptyList(),
        )
        val validation = validatePointRewrite(
            prompts,
            "Corpo revisado com João 17:17 e Mateus 24:14.",
        )
        assertFalse(validation.ok)
        assertTrue(validation.newRefs.any { it.contains("Mateus") })
    }
}
