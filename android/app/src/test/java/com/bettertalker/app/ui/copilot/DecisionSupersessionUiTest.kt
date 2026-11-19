package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.DecisionSubject
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.SupersessionResult
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.1B2C — regras de apresentação da confirmação de substituição. */
class DecisionSupersessionUiTest {

    private fun decision(
        id: String,
        noteId: String = "n1",
        sectionId: String = "s1",
        subjectKey: String? = DecisionSubject.SECTION_APPROACH.key,
        state: DecisionState = DecisionState.ACTIVE,
        link: SectionLink = SectionLink.VALID,
        source: DecisionSource = DecisionSource.STRUCTURE_ACCEPT,
        sourceProposalId: String? = "prop-$id",
        sourceMessageId: String? = null,
        text: String = "decisão $id",
        candidateSectionId: String? = null,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
    ): DecisionRecord = DecisionRecord(
        id = id,
        noteId = noteId,
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = state,
        sectionLink = link,
        applicationState = ApplicationState.NOT_APPLIED,
        text = text,
        source = source,
        sectionId = sectionId,
        originalSectionId = sectionId,
        candidateSectionId = candidateSectionId,
        sectionTitleSnapshot = "Ponto 1",
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = 1,
        sectionContentHashSnapshot = "hash-s1",
        originalSectionTitleSnapshot = "Ponto 1",
        originalSectionRoleSnapshot = SectionRole.BODY,
        originalSectionOrderSnapshot = 1,
        originalSectionContentHashSnapshot = "hash-s1",
        subjectKey = subjectKey,
        sourceMessageId = sourceMessageId,
        sourceProposalId = sourceProposalId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
    )

    private fun prompt(
        previous: DecisionRecord = decision("a", text = "Começar com uma pergunta."),
        replacement: DecisionRecord = decision("b", text = "Começar com uma experiência."),
    ): DecisionSupersessionPrompt = requireNotNull(
        buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)),
    )

    @Test
    fun candidatoUnicoConhecidoGeraPromptComTextosReais() {
        val previous = decision("a", text = "Começar com uma pergunta.")
        val replacement = decision("b", text = "Começar com uma experiência.")

        val result = buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement))

        assertNotNull(result)
        assertEquals(previous, result!!.previous)
        assertEquals(replacement, result.replacement)
        assertEquals("n1", result.noteId)
        assertEquals("supconf-b-a", result.confirmationId)
        assertFalse(result.isSubmitting)
        assertNull(result.outcome)
    }

    @Test
    fun candidatoLegadoGeraPrompt() {
        val previous = decision(
            "a",
            subjectKey = null,
            text = "Começar com uma pergunta.",
        )
        val replacement = decision(
            "b",
            subjectKey = null,
            text = "Começar com uma experiência.",
        )

        val result = buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement))

        assertNotNull(result)
        assertEquals(previous, result!!.previous)
    }

    @Test
    fun assuntosDiferentesNaoGeramPrompt() {
        val previous = decision("a", subjectKey = DecisionSubject.SECTION_OPENING.key)
        val replacement = decision("b", subjectKey = DecisionSubject.SECTION_ILLUSTRATION.key)

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)))
    }

    @Test
    fun notasDiferentesNaoGeramPrompt() {
        val previous = decision("a", noteId = "n2")
        val replacement = decision("b", noteId = "n1")

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)))
    }

    @Test
    fun secoesDiferentesNaoGeramPrompt() {
        val previous = decision("a", sectionId = "s2")
        val replacement = decision("b", sectionId = "s1")

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)))
    }

    @Test
    fun mesmoEventoNaoGeraPrompt() {
        val replacement = decision("b")
        assertNull(
            buildDecisionSupersessionPrompt(replacement, listOf(replacement)),
        )
    }

    @Test
    fun multiplosCandidatosNaoGeramPrompt() {
        val first = decision("a")
        val second = decision("c")
        val replacement = decision("b")

        assertNull(
            buildDecisionSupersessionPrompt(
                replacement,
                listOf(first, second, replacement),
            ),
        )
    }

    @Test
    fun assuntoDesconhecidoNaoGeraPrompt() {
        val previous = decision("a", subjectKey = "custom.foo")
        val replacement = decision("b", subjectKey = "custom.foo")

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)))
    }

    @Test
    fun decisaoInativaNaoGeraPrompt() {
        val previous = decision("a")
        val replacement = decision("b", state = DecisionState.SUPERSEDED)

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)))
    }

    @Test
    fun orfaEPendenteNaoGeramPrompt() {
        val replacement = decision("b")
        val orphan = decision("a", link = SectionLink.ORPHANED)
        val pending = decision(
            "c",
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(orphan, replacement)))
        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(pending, replacement)))
    }

    @Test
    fun provenienciaInsuficienteNaoGeraPrompt() {
        val previous = decision(
            "a",
            source = DecisionSource.EXPLICIT_USER,
            sourceProposalId = null,
            sourceMessageId = null,
        )
        val replacement = decision(
            "b",
            source = DecisionSource.EXPLICIT_USER,
            sourceProposalId = null,
            sourceMessageId = null,
        )

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)))
    }

    @Test
    fun textoParecidoNaoAutorizaPrompt() {
        val previous = decision(
            "a",
            subjectKey = DecisionSubject.SECTION_OPENING.key,
            text = "Começar com uma pergunta.",
        )
        val replacement = decision(
            "b",
            subjectKey = DecisionSubject.SECTION_ILLUSTRATION.key,
            text = "Começar com uma pergunta.",
        )

        assertNull(buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement)))
    }

    @Test
    fun confirmacaoUsaIdsNotaEstadoETimestampObservados() {
        val result = prompt().toConfirmation(now = 30L)

        assertEquals("supconf-b-a", result.confirmationId)
        assertEquals("n1", result.noteId)
        assertEquals("a", result.previousDecisionId)
        assertEquals("b", result.replacementDecisionId)
        assertEquals(10L, result.expectedPreviousUpdatedAt)
        assertEquals(DecisionState.ACTIVE, result.expectedPreviousState)
        assertEquals(30L, result.confirmedAt)
    }

    @Test
    fun resultadosMapeiamMensagensSemPrometerAlteracaoDoTexto() {
        val base = prompt()
        val committed = base.withResult(
            SupersessionResult.Committed(base.previous, base.replacement),
        )
        assertEquals(DecisionSupersessionOutcome.SUCCESS, committed.outcome)
        assertTrue(committed.message!!.contains("texto do discurso não foi alterado"))
        assertFalse(committed.isSubmitting)

        val already = base.withResult(
            SupersessionResult.AlreadyApplied(base.previous, base.replacement),
        )
        assertEquals(DecisionSupersessionOutcome.ALREADY_APPLIED, already.outcome)

        val stale = base.withResult(
            SupersessionResult.Stale("a", expectedUpdatedAt = 10L, actualUpdatedAt = 11L),
        )
        assertEquals(DecisionSupersessionOutcome.STALE, stale.outcome)
        assertTrue(stale.canRefresh())
        assertFalse(stale.canRetry())

        val conflict = base.withResult(
            SupersessionResult.Conflict(
                com.bettertalker.app.data.copilot.SupersessionConflictReason
                    .REPLACEMENT_ALREADY_LINKED,
            ),
        )
        assertEquals(DecisionSupersessionOutcome.CONFLICT, conflict.outcome)
        assertFalse(conflict.message!!.contains("sucesso"))
        assertFalse(conflict.canRetry())
        assertTrue(conflict.isFinished())

        val ineligible = base.withResult(
            SupersessionResult.Ineligible(
                com.bettertalker.app.data.copilot.SupersessionIneligibleReason
                    .PREVIOUS_NOT_ACTIVE,
            ),
        )
        assertEquals(DecisionSupersessionOutcome.INELIGIBLE, ineligible.outcome)
        assertTrue(ineligible.isFinished())

        val ambiguous = base.withResult(
            SupersessionResult.Ambiguous(
                com.bettertalker.app.data.copilot.SupersessionAmbiguityReason
                    .UNKNOWN_SUBJECT,
            ),
        )
        assertEquals(DecisionSupersessionOutcome.AMBIGUOUS, ambiguous.outcome)

        val notFound = base.withResult(SupersessionResult.NotFound("a"))
        assertEquals(DecisionSupersessionOutcome.NOT_FOUND, notFound.outcome)
    }

    @Test
    fun submissaoEErroControlamBotoes() {
        val started = prompt().startSubmission()
        assertTrue(started.isSubmitting)
        assertNull(started.message)

        val error = started.withUnexpectedError()
        assertFalse(error.isSubmitting)
        assertEquals(DecisionSupersessionOutcome.ERROR, error.outcome)
        assertTrue(error.canRetry())
        assertFalse(error.isFinished())

        val refreshFailed = prompt().withRefreshUnavailable()
        assertEquals(
            DecisionSupersessionOutcome.REFRESH_UNAVAILABLE,
            refreshFailed.outcome,
        )
        assertTrue(refreshFailed.isFinished())
    }

    @Test
    fun promptNaoAlteraEstadoNemTexto() {
        val previous = decision("a")
        val replacement = decision("b")
        val result = buildDecisionSupersessionPrompt(replacement, listOf(previous, replacement))!!

        assertEquals(DecisionState.ACTIVE, result.previous.decisionState)
        assertEquals(DecisionState.ACTIVE, result.replacement.decisionState)
        assertEquals("decisão a", result.previous.text)
        assertEquals("decisão b", result.replacement.text)
    }

    @Test
    fun sessaoSuprimeRepeticaoDoMesmoPrompt() {
        val session = DecisionSupersessionSession()
        val first = prompt()

        assertTrue(session.shouldOffer(first))
        session.suppress(first)

        assertFalse(session.shouldOffer(first))
        assertFalse(session.shouldOffer(prompt()))
        assertFalse(session.shouldOffer(null))
    }

    @Test
    fun sessaoNaoSuprimeOutroRelacionamento() {
        val session = DecisionSupersessionSession()
        val first = prompt()
        val other = buildDecisionSupersessionPrompt(
            decision("c", createdAt = 20L),
            listOf(decision("a"), decision("c", createdAt = 20L)),
        )!!

        session.suppress(first)

        assertTrue(session.shouldOffer(other))
    }
}
