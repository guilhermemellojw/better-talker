package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.1A1 — invariantes puras do domínio de decisões. */
class DecisionMemoryTest {

    private fun snapshot(
        id: String = "s1",
        title: String = "Ponto 1",
        order: Int = 1,
        hash: String = "hash-s1",
    ) = SectionSnapshot(
        id = id,
        noteId = "n1",
        role = SectionRole.BODY,
        title = title,
        order = order,
        contentHash = hash,
    )

    private fun sectionDecision(
        id: String = "d1",
        sectionId: String = "s1",
        originalSectionId: String = sectionId,
        candidateSectionId: String? = null,
        link: SectionLink = SectionLink.VALID,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        source: DecisionSource = DecisionSource.EXPLICIT_USER,
        title: String = "Ponto 1",
        order: Int = 1,
        appliedHash: String? = "hash-s1",
        supersedesId: String? = null,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
        removedAt: Long? = if (state == DecisionState.REMOVED) 10L else null,
    ) = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = kind,
        decisionState = state,
        sectionLink = link,
        applicationState = application,
        text = "usar contraste na abertura",
        source = source,
        sectionId = sectionId,
        originalSectionId = originalSectionId,
        candidateSectionId = candidateSectionId,
        sectionTitleSnapshot = title,
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = order,
        sectionContentHashSnapshot = "hash-s1",
        supersedesId = supersedesId,
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 10L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) appliedHash else null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = removedAt,
    )

    private fun globalDecision(
        id: String = "g1",
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
    ) = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.NOTE,
        kind = kind,
        decisionState = state,
        sectionLink = SectionLink.NOT_APPLICABLE,
        applicationState = application,
        text = "tom pastoral em toda a nota",
        source = DecisionSource.EXPLICIT_USER,
        createdAt = 10L,
        updatedAt = 10L,
        removedAt = if (state == DecisionState.REMOVED) 10L else null,
    )

    @Test
    fun decisaoGlobalNaoTemSecao() {
        val d = globalDecision()
        assertEquals(DecisionScope.NOTE, d.scope)
        assertEquals(SectionLink.NOT_APPLICABLE, d.sectionLink)
        assertEquals(null, d.sectionId)
        assertEquals(null, d.originalSectionId)
    }

    @Test
    fun decisaoDeSecaoPreservaIdEEntidadeOriginal() {
        val d = sectionDecision()
        assertEquals(DecisionScope.SECTION, d.scope)
        assertEquals(SectionLink.VALID, d.sectionLink)
        assertEquals("s1", d.sectionId)
        assertEquals("s1", d.originalSectionId)
    }

    @Test
    fun globalComSecaoEhRejeitada() {
        assertThrows(IllegalArgumentException::class.java) {
            DecisionRecord(
                id = "x", noteId = "n1", scope = DecisionScope.NOTE,
                kind = DecisionKind.CONFIRMED, source = DecisionSource.EXPLICIT_USER,
                text = "x", sectionId = "s1", originalSectionId = "s1",
                sectionLink = SectionLink.VALID,
                sectionTitleSnapshot = "Ponto", sectionRoleSnapshot = SectionRole.BODY,
                sectionOrderSnapshot = 1, sectionContentHashSnapshot = "h",
                createdAt = 1,
            )
        }
    }

    @Test
    fun secaoSemSnapshotEhRejeitada() {
        assertThrows(IllegalArgumentException::class.java) {
            DecisionRecord(
                id = "x", noteId = "n1", scope = DecisionScope.SECTION,
                kind = DecisionKind.CONFIRMED, source = DecisionSource.EXPLICIT_USER,
                text = "x", sectionId = "s1", originalSectionId = "s1",
                sectionLink = SectionLink.VALID, createdAt = 1,
            )
        }
    }

    @Test
    fun pendingExigeCandidatoDiferenteDaAssociacaoAtual() {
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(link = SectionLink.PENDING_RECONFIRMATION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(
                link = SectionLink.PENDING_RECONFIRMATION,
                candidateSectionId = "s1",
            )
        }
        val pending = sectionDecision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )
        assertEquals("s1", pending.sectionId)
        assertEquals("s2", pending.candidateSectionId)
    }

    @Test
    fun resolvedSoExisteParaOpen() {
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(kind = DecisionKind.CONFIRMED, state = DecisionState.RESOLVED)
        }
        val open = sectionDecision(kind = DecisionKind.OPEN, state = DecisionState.RESOLVED)
        assertEquals(DecisionState.RESOLVED, open.decisionState)
    }

    @Test
    fun openNaoPodeSerSuperseded() {
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(kind = DecisionKind.OPEN, state = DecisionState.SUPERSEDED)
        }
    }

    @Test
    fun removedExigeRemovedAtEOutrosEstadosNaoAceitamRemovedAt() {
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(state = DecisionState.REMOVED, removedAt = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(state = DecisionState.ACTIVE, removedAt = 10L)
        }
        val removed = sectionDecision(state = DecisionState.REMOVED)
        assertEquals(DecisionState.REMOVED, removed.decisionState)
        assertEquals(10L, removed.removedAt)
    }

    @Test
    fun aplicacaoSoExisteParaConfirmedEExigeHistorico() {
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(kind = DecisionKind.PREFERENCE, application = ApplicationState.APPLIED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(application = ApplicationState.APPLIED, appliedHash = null)
        }
        val applied = sectionDecision(application = ApplicationState.APPLIED)
        assertEquals(ApplicationState.APPLIED, applied.applicationState)
        assertTrue(applied.appliedAt != null)
        assertTrue(!applied.appliedHash.isNullOrBlank())
    }

    @Test
    fun structureAcceptSoConfirmaConfirmed() {
        assertThrows(IllegalArgumentException::class.java) {
            sectionDecision(
                kind = DecisionKind.PREFERENCE,
                source = DecisionSource.STRUCTURE_ACCEPT,
            )
        }
        val d = sectionDecision(source = DecisionSource.STRUCTURE_ACCEPT)
        assertEquals(DecisionKind.CONFIRMED, d.kind)
    }

    @Test
    fun remocaoReversivelPreservaAplicacaoEAssociacaoOriginal() {
        val applied = sectionDecision(application = ApplicationState.APPLIED)
        val orphan = DecisionTransitions.orphanForReversibleRemoval(applied, now = 20L)
        assertEquals(SectionLink.ORPHANED, orphan.sectionLink)
        assertEquals(ApplicationState.APPLIED, orphan.applicationState)
        assertEquals("s1", orphan.sectionId)
        assertEquals("s1", orphan.originalSectionId)
        assertEquals(DecisionState.ACTIVE, orphan.decisionState)
    }

    @Test
    fun remapeamentoPreservaOriginalEMantemAplicacaoSeHashBate() {
        val pending = sectionDecision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
            application = ApplicationState.APPLIED,
        )
        val target = snapshot(id = "s2", title = "Ponto 2", order = 2, hash = "hash-s1")
        val remapped = DecisionTransitions.confirmRemap(pending, target, now = 20L)
        assertEquals("s2", remapped.sectionId)
        assertEquals("s1", remapped.originalSectionId)
        assertEquals(SectionLink.VALID, remapped.sectionLink)
        assertEquals(null, remapped.candidateSectionId)
        assertEquals(ApplicationState.APPLIED, remapped.applicationState)
    }

    @Test
    fun remapeamentoComConteudoDiferenteMarcaAplicacaoStale() {
        val pending = sectionDecision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
            application = ApplicationState.APPLIED,
        )
        val target = snapshot(id = "s2", title = "Ponto 2", order = 2, hash = "hash-s2")
        val remapped = DecisionTransitions.confirmRemap(pending, target, now = 20L)
        assertEquals(ApplicationState.STALE, remapped.applicationState)
    }

    @Test
    fun removedEhTerminalParaTransicoes() {
        val removed = sectionDecision(state = DecisionState.REMOVED)
        assertThrows(IllegalArgumentException::class.java) {
            DecisionTransitions.orphanForReversibleRemoval(removed, now = 20L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DecisionTransitions.markPendingReconfirmation(removed, "s2", now = 20L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DecisionTransitions.confirmRemap(removed, snapshot("s2"), now = 20L)
        }
        assertEquals(removed, DecisionTransitions.removePermanently(removed, now = 20L))
    }

    @Test
    fun supersessaoApontaDoNovoParaOAnteriorESoMarcaOAnterior() {
        val previous = sectionDecision(id = "d1")
        val replacement = sectionDecision(
            id = "d2",
            supersedesId = previous.id,
            createdAt = 20L,
            updatedAt = 20L,
        )
        val pair = DecisionTransitions.supersede(previous, replacement, now = 20L)
        assertEquals(DecisionState.SUPERSEDED, pair.previous.decisionState)
        assertEquals(DecisionState.ACTIVE, pair.replacement.decisionState)
        assertEquals("d1", pair.replacement.supersedesId)
        assertEquals(null, pair.previous.supersedesId)
    }

    @Test
    fun supersessaoInvalidaNaoConfundeDirecao() {
        val previous = sectionDecision(id = "d1")
        val wrongDirection = sectionDecision(
            id = "d2",
            supersedesId = null,
            createdAt = 20L,
            updatedAt = 20L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DecisionTransitions.validateSupersession(previous, wrongDirection)
        }
        val backwards = sectionDecision(
            id = "d0",
            supersedesId = previous.id,
            createdAt = 5L,
            updatedAt = 5L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DecisionTransitions.validateSupersession(previous, backwards)
        }
    }

    @Test
    fun snapshotDeSecaoEhObrigatorioParaDecisaoDeSecao() {
        val snapshot = snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            SectionSnapshot("", "n1", SectionRole.BODY, "Ponto", 1, "h")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SectionSnapshot("s1", "n1", SectionRole.BODY, "Ponto", -1, "h")
        }
        assertEquals("s1", snapshot.id)
    }
}
