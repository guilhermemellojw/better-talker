package com.bettertalker.app.data.db

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 1C.2.1A2 — round-trip completo entre DecisionRecord e DecisionEntity. */
class DecisionRecordMapperTest {

    private fun noteDecision(
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        source: DecisionSource = DecisionSource.EXPLICIT_USER,
    ) = DecisionRecord(
        id = "g1",
        noteId = "n1",
        scope = DecisionScope.NOTE,
        kind = kind,
        decisionState = state,
        sectionLink = SectionLink.NOT_APPLICABLE,
        applicationState = application,
        text = "tom pastoral em toda a nota",
        source = source,
        subjectKey = "tom",
        sourceMessageId = "msg-1",
        sourceProposalId = "prop-1",
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 11L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) "hash-aplicado" else null,
        createdAt = 10L,
        updatedAt = 20L,
        removedAt = if (state == DecisionState.REMOVED) 20L else null,
    )

    private fun sectionDecision(
        link: SectionLink = SectionLink.VALID,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        candidateSectionId: String? = null,
    ) = DecisionRecord(
        id = "d1",
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = state,
        sectionLink = link,
        applicationState = application,
        text = "usar contraste na abertura",
        source = DecisionSource.STRUCTURE_ACCEPT,
        sectionId = "s2",
        originalSectionId = "s1",
        candidateSectionId = candidateSectionId,
        sectionTitleSnapshot = "Ponto B",
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = 2,
        sectionContentHashSnapshot = "hash-b",
        originalSectionTitleSnapshot = "Ponto A",
        originalSectionRoleSnapshot = SectionRole.INTRO,
        originalSectionOrderSnapshot = 1,
        originalSectionContentHashSnapshot = "hash-a",
        supersedesId = "d0",
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 12L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) "hash-aplicado" else null,
        createdAt = 10L,
        updatedAt = 20L,
        removedAt = if (state == DecisionState.REMOVED) 20L else null,
    )

    @Test
    fun notaRoundTripPreservaTodosOsCampos() {
        val original = noteDecision(application = ApplicationState.APPLIED)
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun secaoRoundTripPreservaSnapshotAtualEOriginal() {
        val original = sectionDecision(application = ApplicationState.STALE)
        val entity = original.toEntity()
        assertEquals("s2", entity.sectionId)
        assertEquals("s1", entity.originalSectionId)
        assertEquals("Ponto B", entity.sectionTitleSnapshot)
        assertEquals("Ponto A", entity.originalSectionTitleSnapshot)
        assertEquals("BODY", entity.sectionRoleSnapshot)
        assertEquals("INTRO", entity.originalSectionRoleSnapshot)
        assertEquals(original, entity.toDomain())
    }

    @Test
    fun roundTripCobreTodosOsEstadosValidos() {
        val decisions = listOf(
            sectionDecision(),
            sectionDecision(link = SectionLink.ORPHANED, application = ApplicationState.APPLIED),
            sectionDecision(
                link = SectionLink.PENDING_RECONFIRMATION,
                candidateSectionId = "s9",
                application = ApplicationState.STALE,
            ),
            sectionDecision(
                state = DecisionState.SUPERSEDED,
                application = ApplicationState.APPLIED,
            ),
            noteDecision(
                kind = DecisionKind.OPEN,
                state = DecisionState.RESOLVED,
                source = DecisionSource.DECISION_CONFIRMATION,
            ),
            noteDecision(
                kind = DecisionKind.PREFERENCE,
                state = DecisionState.REMOVED,
            ),
            noteDecision(application = ApplicationState.APPLIED),
            DecisionRecord(
                id = "p1",
                noteId = "n1",
                scope = DecisionScope.SECTION,
                kind = DecisionKind.REJECTED,
                sectionLink = SectionLink.VALID,
                text = "não usar tom informal",
                source = DecisionSource.EXPLICIT_USER,
                sectionId = "s1",
                originalSectionId = "s1",
                sectionTitleSnapshot = "Ponto A",
                sectionRoleSnapshot = SectionRole.BODY,
                sectionOrderSnapshot = 1,
                sectionContentHashSnapshot = "hash-a",
                originalSectionTitleSnapshot = "Ponto A",
                originalSectionRoleSnapshot = SectionRole.BODY,
                originalSectionOrderSnapshot = 1,
                originalSectionContentHashSnapshot = "hash-a",
                createdAt = 10L,
                updatedAt = 10L,
            ),
        )

        decisions.forEach { original ->
            assertEquals(original, original.toEntity().toDomain())
        }
    }

    @Test
    fun enumsSaoLidosDeFormaEstrita() {
        val entity = noteDecision().toEntity().copy(scope = "DESCONHECIDO")
        assertThrows(IllegalArgumentException::class.java) { entity.toDomain() }
    }
}
