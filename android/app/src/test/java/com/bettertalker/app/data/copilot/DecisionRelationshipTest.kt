package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.1B2A — contratos puros de relacionamento entre decisões. */
class DecisionRelationshipTest {

    private fun decision(
        id: String = "d1",
        noteId: String = "n1",
        scope: DecisionScope = DecisionScope.SECTION,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        link: SectionLink =
            if (scope == DecisionScope.SECTION) SectionLink.VALID else SectionLink.NOT_APPLICABLE,
        sectionId: String? = if (scope == DecisionScope.SECTION) "s1" else null,
        originalSectionId: String? = sectionId,
        candidateSectionId: String? = null,
        subjectKey: String? = null,
        source: DecisionSource = DecisionSource.EXPLICIT_USER,
        text: String = "usar uma abordagem clara",
        sourceProposalId: String? = "prop-$id",
        sourceMessageId: String? = null,
        supersedesId: String? = null,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
    ): DecisionRecord {
        val section = scope == DecisionScope.SECTION
        return DecisionRecord(
            id = id,
            noteId = noteId,
            scope = scope,
            kind = kind,
            decisionState = state,
            sectionLink = link,
            applicationState = application,
            text = text,
            source = source,
            sectionId = sectionId,
            originalSectionId = originalSectionId,
            candidateSectionId = candidateSectionId,
            sectionTitleSnapshot = if (section) "Ponto 1" else null,
            sectionRoleSnapshot = if (section) SectionRole.BODY else null,
            sectionOrderSnapshot = if (section) 1 else null,
            sectionContentHashSnapshot = if (section) "hash-s1" else null,
            originalSectionTitleSnapshot = if (section) "Ponto 1" else null,
            originalSectionRoleSnapshot = if (section) SectionRole.BODY else null,
            originalSectionOrderSnapshot = if (section) 1 else null,
            originalSectionContentHashSnapshot = if (section) "hash-s1" else null,
            subjectKey = subjectKey,
            sourceMessageId = sourceMessageId,
            sourceProposalId = sourceProposalId,
            supersedesId = supersedesId,
            appliedAt = if (application != ApplicationState.NOT_APPLIED) 11L else null,
            appliedHash = if (application != ApplicationState.NOT_APPLIED) "hash-aplicado" else null,
            createdAt = createdAt,
            updatedAt = updatedAt,
            removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
        )
    }

    private fun relationship(
        incoming: DecisionRecord,
        vararg existing: DecisionRecord,
    ): DecisionRelationship = classifyDecisionRelationship(incoming, existing.toList())

    @Test
    fun mesmoEventoAtivoEhSameEvent() {
        val d = decision()
        val result = relationship(d, d)

        assertTrue(result is DecisionRelationship.SameEvent)
        assertEquals(d, (result as DecisionRelationship.SameEvent).existing)
    }

    @Test
    fun mesmoEventoComEstadoPosteriorAindaEhSameEvent() {
        val incoming = decision()
        val existing = incoming.copy(decisionState = DecisionState.SUPERSEDED)

        val result = relationship(incoming, existing)

        assertTrue(result is DecisionRelationship.SameEvent)
        assertEquals(existing, (result as DecisionRelationship.SameEvent).existing)
    }

    @Test
    fun mesmoIdComIdentidadeDiferenteEhAmbiguous() {
        val incoming = decision(id = "d1")
        val otherNote = decision(id = "d1", noteId = "n2")
        val result = relationship(incoming, otherNote)

        assertTrue(result is DecisionRelationship.Ambiguous)
        assertEquals(
            AmbiguityReason.IDENTITY_COLLISION,
            (result as DecisionRelationship.Ambiguous).reason,
        )
        assertEquals(listOf(otherNote), result.candidates)
    }

    @Test
    fun mesmoIdComProvenienciaDiferenteEhAmbiguous() {
        val incoming = decision(id = "d1", sourceProposalId = "prop-a")
        val existing = decision(id = "d1", sourceProposalId = "prop-b")

        val result = relationship(incoming, existing)

        assertTrue(result is DecisionRelationship.Ambiguous)
        assertEquals(
            AmbiguityReason.IDENTITY_COLLISION,
            (result as DecisionRelationship.Ambiguous).reason,
        )
    }

    @Test
    fun mesmoIdComNaturezaDiferenteEhAmbiguous() {
        val incoming = decision(id = "d1", kind = DecisionKind.CONFIRMED)
        val existing = decision(id = "d1", kind = DecisionKind.PREFERENCE)

        val result = relationship(incoming, existing)

        assertTrue(result is DecisionRelationship.Ambiguous)
        assertEquals(
            AmbiguityReason.IDENTITY_COLLISION,
            (result as DecisionRelationship.Ambiguous).reason,
        )
    }

    @Test
    fun assuntosDiferentesSaoIndependent() {
        val incoming = decision(id = "d1", subjectKey = DecisionSubject.SECTION_OPENING.key)
        val existing = decision(id = "d2", subjectKey = DecisionSubject.SECTION_ILLUSTRATION.key)

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun mesmoAssuntoMesmaSecaoEhPotentialOverlap() {
        val incoming = decision(id = "d1", subjectKey = DecisionSubject.SECTION_APPROACH.key)
        val existing = decision(id = "d2", subjectKey = DecisionSubject.SECTION_APPROACH.key)

        val result = relationship(incoming, existing)

        assertTrue(result is DecisionRelationship.PotentialOverlap)
        assertEquals(existing, (result as DecisionRelationship.PotentialOverlap).candidate)
    }

    @Test
    fun mesmoAssuntoEmSecaoDiferenteEhIndependent() {
        val incoming = decision(
            id = "d1",
            sectionId = "s1",
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )
        val existing = decision(
            id = "d2",
            sectionId = "s2",
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun mesmaSecaoEmNotaDiferenteEhIndependent() {
        val incoming = decision(
            id = "d1",
            noteId = "n1",
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )
        val existing = decision(
            id = "d2",
            noteId = "n2",
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun legadoAmbosStructureAcceptEhLegacyPotentialOverlap() {
        val incoming = decision(
            id = "d1",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = null,
        )
        val existing = decision(
            id = "d2",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = null,
        )

        val result = relationship(incoming, existing)

        assertTrue(result is DecisionRelationship.LegacyPotentialOverlap)
        assertEquals(existing, (result as DecisionRelationship.LegacyPotentialOverlap).candidate)
    }

    @Test
    fun legadoUmNovoEstruturaEhLegacyPotentialOverlap() {
        val incoming = decision(
            id = "d1",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )
        val existing = decision(
            id = "d2",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = null,
        )

        val result = relationship(incoming, existing)

        assertTrue(result is DecisionRelationship.LegacyPotentialOverlap)
        assertEquals(existing, (result as DecisionRelationship.LegacyPotentialOverlap).candidate)
    }

    @Test
    fun legadoSemAmbosStructureAcceptEhIndependent() {
        val incoming = decision(id = "d1", subjectKey = null)
        val existing = decision(id = "d2", subjectKey = null)

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun legadoUmNovoNaoEstruturaEhIndependent() {
        val incoming = decision(id = "d1", subjectKey = null)
        val existing = decision(
            id = "d2",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun multiplosCandidatosEhAmbiguous() {
        val incoming = decision(id = "d1", subjectKey = DecisionSubject.SECTION_APPROACH.key)
        val first = decision(id = "d2", subjectKey = DecisionSubject.SECTION_APPROACH.key)
        val second = decision(id = "d3", subjectKey = DecisionSubject.SECTION_APPROACH.key)

        val result = relationship(incoming, first, second)

        assertTrue(result is DecisionRelationship.Ambiguous)
        assertEquals(
            AmbiguityReason.MULTIPLE_CANDIDATES,
            (result as DecisionRelationship.Ambiguous).reason,
        )
        assertEquals(listOf(first, second), result.candidates)
    }

    @Test
    fun misturaConhecidoELegadoEhAmbiguous() {
        val incoming = decision(
            id = "d1",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )
        val known = decision(
            id = "d2",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )
        val legacy = decision(
            id = "d3",
            source = DecisionSource.STRUCTURE_ACCEPT,
            subjectKey = null,
        )

        val result = relationship(incoming, known, legacy)

        assertTrue(result is DecisionRelationship.Ambiguous)
        assertEquals(
            AmbiguityReason.MULTIPLE_CANDIDATES,
            (result as DecisionRelationship.Ambiguous).reason,
        )
        assertEquals(listOf(known, legacy), result.candidates)
    }

    @Test
    fun decisoesNaoElegiveisNaoSaoCandidatas() {
        val incoming = decision(id = "d1", subjectKey = DecisionSubject.SECTION_APPROACH.key)
        val ineligible = listOf(
            decision(
                id = "superseded",
                state = DecisionState.SUPERSEDED,
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
            decision(
                id = "removed",
                state = DecisionState.REMOVED,
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
            decision(
                id = "orphaned",
                link = SectionLink.ORPHANED,
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
            decision(
                id = "pending",
                link = SectionLink.PENDING_RECONFIRMATION,
                candidateSectionId = "s2",
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
            decision(
                id = "open",
                kind = DecisionKind.OPEN,
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
            decision(
                id = "resolved",
                kind = DecisionKind.OPEN,
                state = DecisionState.RESOLVED,
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
            decision(
                id = "rejected",
                kind = DecisionKind.REJECTED,
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
            decision(
                id = "preference",
                kind = DecisionKind.PREFERENCE,
                subjectKey = DecisionSubject.SECTION_APPROACH.key,
            ),
        )

        assertEquals(
            DecisionRelationship.Independent,
            classifyDecisionRelationship(incoming, ineligible),
        )
    }

    @Test
    fun incomingTerminalNaoParticipa() {
        val superseded = decision(state = DecisionState.SUPERSEDED)
        val removed = decision(state = DecisionState.REMOVED)

        assertEquals(
            DecisionRelationship.Ineligible(IneligibleReason.NOT_ACTIVE),
            relationship(superseded),
        )
        assertEquals(
            DecisionRelationship.Ineligible(IneligibleReason.NOT_ACTIVE),
            relationship(removed),
        )
    }

    @Test
    fun incomingNaoConfirmadoNaoParticipa() {
        val open = decision(kind = DecisionKind.OPEN)
        val rejected = decision(kind = DecisionKind.REJECTED)
        val preference = decision(kind = DecisionKind.PREFERENCE)

        listOf(open, rejected, preference).forEach { incoming ->
            assertEquals(
                DecisionRelationship.Ineligible(IneligibleReason.NOT_CONFIRMED),
                relationship(incoming),
            )
        }
    }

    @Test
    fun incomingOrfaoOuPendenteNaoParticipa() {
        val orphaned = decision(link = SectionLink.ORPHANED)
        val pending = decision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )

        listOf(orphaned, pending).forEach { incoming ->
            assertEquals(
                DecisionRelationship.Ineligible(IneligibleReason.SECTION_LINK_NOT_VALID),
                relationship(incoming),
            )
        }
    }

    @Test
    fun textoIgualNaoCriaRelacao() {
        val incoming = decision(
            id = "d1",
            text = "texto idêntico",
            subjectKey = DecisionSubject.SECTION_OPENING.key,
        )
        val existing = decision(
            id = "d2",
            text = "texto idêntico",
            subjectKey = DecisionSubject.SECTION_ILLUSTRATION.key,
        )

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun assuntoDesconhecidoIgualEhAmbiguous() {
        val incoming = decision(id = "d1", subjectKey = "custom.foo")
        val existing = decision(id = "d2", subjectKey = "custom.foo")

        val result = relationship(incoming, existing)

        assertTrue(result is DecisionRelationship.Ambiguous)
        assertEquals(
            AmbiguityReason.UNKNOWN_SUBJECT,
            (result as DecisionRelationship.Ambiguous).reason,
        )
        assertEquals(listOf(existing), result.candidates)
    }

    @Test
    fun assuntoDesconhecidoDiferenteEhIndependent() {
        val incoming = decision(id = "d1", subjectKey = "custom.foo")
        val existing = decision(id = "d2", subjectKey = "custom.bar")

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun assuntoDeOutroEscopoEhIneligible() {
        val incoming = decision(
            scope = DecisionScope.NOTE,
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
        )

        assertEquals(
            DecisionRelationship.Ineligible(IneligibleReason.SUBJECT_SCOPE_MISMATCH),
            relationship(incoming),
        )
    }

    @Test
    fun notaComMesmoAssuntoEhPotentialOverlap() {
        val incoming = decision(
            id = "d1",
            scope = DecisionScope.NOTE,
            subjectKey = DecisionSubject.NOTE_TONE.key,
        )
        val existing = decision(
            id = "d2",
            scope = DecisionScope.NOTE,
            subjectKey = DecisionSubject.NOTE_TONE.key,
        )

        assertTrue(relationship(incoming, existing) is DecisionRelationship.PotentialOverlap)
    }

    @Test
    fun notaComAssuntoDiferenteEhIndependent() {
        val incoming = decision(
            id = "d1",
            scope = DecisionScope.NOTE,
            subjectKey = DecisionSubject.NOTE_TONE.key,
        )
        val existing = decision(
            id = "d2",
            scope = DecisionScope.NOTE,
            subjectKey = DecisionSubject.NOTE_STRUCTURE.key,
        )

        assertEquals(DecisionRelationship.Independent, relationship(incoming, existing))
    }

    @Test
    fun aplicacaoNaoAlteraPotencialOverlap() {
        val incoming = decision(id = "d1", subjectKey = DecisionSubject.SECTION_APPROACH.key)
        val applied = decision(
            id = "d2",
            subjectKey = DecisionSubject.SECTION_APPROACH.key,
            application = ApplicationState.APPLIED,
        )

        assertTrue(relationship(incoming, applied) is DecisionRelationship.PotentialOverlap)
    }

    @Test
    fun idsDuplicadosNoHistoricoSaoAmbiguous() {
        val incoming = decision(id = "d1", subjectKey = DecisionSubject.SECTION_APPROACH.key)
        val duplicateA = decision(id = "d2", subjectKey = DecisionSubject.SECTION_APPROACH.key)
        val duplicateB = duplicateA.copy(text = "outro texto")

        val result = classifyDecisionRelationship(incoming, listOf(duplicateA, duplicateB))

        assertTrue(result is DecisionRelationship.Ambiguous)
        assertEquals(
            AmbiguityReason.IDENTITY_COLLISION,
            (result as DecisionRelationship.Ambiguous).reason,
        )
    }

    @Test
    fun vocabularioDeAssuntosMapeiaChavesEEscopos() {
        assertEquals(
            DecisionSubject.SECTION_APPROACH,
            DecisionSubject.fromKey("section.approach"),
        )
        assertEquals(DecisionSubject.NOTE_TONE, DecisionSubject.fromKey("note.tone"))
        assertEquals(null, DecisionSubject.fromKey(null))
        assertEquals(null, DecisionSubject.fromKey("desconhecido"))
        assertEquals(
            DecisionScope.SECTION,
            DecisionSubject.SECTION_APPROACH.scope,
        )
        assertEquals(DecisionScope.NOTE, DecisionSubject.NOTE_TONE.scope)
        assertEquals(
            DecisionSubject.entries.size,
            DecisionSubject.entries.map { it.key }.distinct().size,
        )
    }
}
