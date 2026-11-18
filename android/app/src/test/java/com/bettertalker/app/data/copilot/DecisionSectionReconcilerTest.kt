package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.1A1 — reconciliação determinística e conservadora. */
class DecisionSectionReconcilerTest {

    private fun snap(
        id: String = "s1",
        title: String = "Ponto 1",
        order: Int = 1,
        hash: String = "h-s1",
    ) = SectionSnapshot(
        id = id,
        noteId = "n1",
        role = SectionRole.BODY,
        title = title,
        order = order,
        contentHash = hash,
    )

    private fun decision(
        id: String = "d1",
        sectionId: String = "s1",
        originalSectionId: String = sectionId,
        candidateSectionId: String? = null,
        link: SectionLink = SectionLink.VALID,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        title: String = "Ponto 1",
        order: Int = 1,
        appliedHash: String? = "h-s1",
        removedAt: Long? = if (state == DecisionState.REMOVED) 10L else null,
    ) = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = kind,
        decisionState = state,
        sectionLink = link,
        applicationState = application,
        text = "usar contraste",
        source = DecisionSource.EXPLICIT_USER,
        sectionId = sectionId,
        originalSectionId = originalSectionId,
        candidateSectionId = candidateSectionId,
        sectionTitleSnapshot = title,
        sectionRoleSnapshot = SectionRole.BODY,
        sectionOrderSnapshot = order,
        sectionContentHashSnapshot = "h-s1",
        appliedAt = if (application != ApplicationState.NOT_APPLIED) 10L else null,
        appliedHash = if (application != ApplicationState.NOT_APPLIED) appliedHash else null,
        createdAt = 10L,
        updatedAt = 10L,
        removedAt = removedAt,
    )

    @Test
    fun decisaoGlobalNaoEhAfetada() {
        val global = DecisionRecord(
            id = "g1", noteId = "n1", scope = DecisionScope.NOTE,
            kind = DecisionKind.CONFIRMED, source = DecisionSource.EXPLICIT_USER,
            text = "tom pastoral", createdAt = 10L,
        )
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(global),
            oldSections = emptyList(),
            newSections = listOf(snap("s2")),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        )
        assertEquals(global, report.decisions.single())
        assertEquals(DecisionScope.NOTE, report.decisions.single().scope)
    }

    @Test
    fun reordenacaoComMesmoIdMantemAssociacao() {
        val d = decision()
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap(order = 1)),
            newSections = listOf(snap(order = 4)),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )
        assertEquals(d, report.decisions.single())
        assertEquals(SectionLink.VALID, report.decisions.single().sectionLink)
    }

    @Test
    fun renomeacaoComMesmoIdMantemAssociacao() {
        val d = decision()
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap(title = "Ponto 1")),
            newSections = listOf(snap(title = "Ponto 1 — revisado")),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )
        assertEquals(d, report.decisions.single())
        assertEquals("s1", report.decisions.single().sectionId)
    }

    @Test
    fun remocaoReversivelMudaSoAssociacaoEPreservaAplicacao() {
        val d = decision(application = ApplicationState.APPLIED)
        val removed = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap()),
            newSections = emptyList(),
            operation = ReconcileOperation.REMOVE,
            now = 20L,
        ).decisions.single()
        assertEquals(SectionLink.ORPHANED, removed.sectionLink)
        assertEquals(ApplicationState.APPLIED, removed.applicationState)
        assertEquals("s1", removed.sectionId)
        assertEquals("s1", removed.originalSectionId)
        assertEquals(DecisionState.ACTIVE, removed.decisionState)

        val undone = DecisionSectionReconciler.reconcile(
            decisions = listOf(removed),
            oldSections = emptyList(),
            newSections = listOf(snap()),
            operation = ReconcileOperation.UNDO_REMOVE,
            now = 30L,
        ).decisions.single()
        assertEquals(SectionLink.VALID, undone.sectionLink)
        assertEquals(ApplicationState.APPLIED, undone.applicationState)
        assertEquals(DecisionState.ACTIVE, undone.decisionState)
    }

    @Test
    fun remocaoDefinitivaProduzRemovedTerminal() {
        val d = decision()
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap()),
            newSections = emptyList(),
            operation = ReconcileOperation.DELETE,
            now = 20L,
        )
        val removed = report.decisions.single()
        assertEquals(DecisionState.REMOVED, removed.decisionState)
        assertEquals(20L, removed.removedAt)
        assertTrue(report.removed.contains(removed))

        val after = DecisionSectionReconciler.reconcile(
            decisions = listOf(removed),
            oldSections = emptyList(),
            newSections = listOf(snap()),
            operation = ReconcileOperation.UNDO_REMOVE,
            now = 30L,
        ).decisions.single()
        assertEquals(DecisionState.REMOVED, after.decisionState)
    }

    @Test
    fun reimportReplaceSugereCandidatoSemRemaperar() {
        val d = decision(sectionId = "s1", originalSectionId = "s1", title = "Ponto 1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", "Ponto 1", 1, "h-s1")),
            newSections = listOf(snap("s2", "Ponto 1", 1, "h-s1")),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        )
        val pending = report.decisions.single()
        assertEquals(SectionLink.PENDING_RECONFIRMATION, pending.sectionLink)
        assertEquals("s1", pending.sectionId)
        assertEquals("s1", pending.originalSectionId)
        assertEquals("s2", pending.candidateSectionId)
        assertTrue(report.pendingReconfirmation.contains(pending))
        assertTrue(report.warnings.isNotEmpty())
    }

    @Test
    fun titulosDuplicadosNaoAutorizamCandidato() {
        val d = decision(title = "Ponto 1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", "Ponto 1", 1, "h-s1")),
            newSections = listOf(
                snap("s2", "Ponto 1", 1, "h-s2"),
                snap("s3", "Ponto 1", 2, "h-s3"),
            ),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        )
        val orphan = report.decisions.single()
        assertEquals(SectionLink.ORPHANED, orphan.sectionLink)
        assertEquals("s1", orphan.sectionId)
        assertEquals(null, orphan.candidateSectionId)
    }

    @Test
    fun tituloSemelhanteNaoTransfereAutomaticamente() {
        val d = decision(title = "Ponto 1", order = 1)
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", "Ponto 1", 1, "h-s1")),
            newSections = listOf(snap("s2", "Ponto 1 — continuação", 3, "h-s2")),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        )
        val result = report.decisions.single()
        assertFalse(result.sectionId == "s2")
        assertEquals("s1", result.sectionId)
        assertEquals("s1", result.originalSectionId)
        assertEquals(SectionLink.ORPHANED, result.sectionLink)
    }

    @Test
    fun remapeamentoExplicitoEDepoisRemocaoMantemOriginal() {
        val pending = decision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )
        val remapped = DecisionTransitions.confirmRemap(
            pending,
            snap("s2", "Ponto 2", 2, "h-s2"),
            now = 20L,
        )
        assertEquals("s2", remapped.sectionId)
        assertEquals("s1", remapped.originalSectionId)
        assertEquals(SectionLink.VALID, remapped.sectionLink)

        val orphan = DecisionSectionReconciler.reconcile(
            decisions = listOf(remapped),
            oldSections = listOf(snap("s2", "Ponto 2", 2, "h-s2")),
            newSections = emptyList(),
            operation = ReconcileOperation.REMOVE,
            now = 30L,
        ).decisions.single()
        assertEquals(SectionLink.ORPHANED, orphan.sectionLink)
        assertEquals("s2", orphan.sectionId)
        assertEquals("s1", orphan.originalSectionId)
    }

    @Test
    fun reimportAppendNaoSugereCandidato() {
        val d = decision(title = "Ponto 1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", "Ponto 1")),
            newSections = listOf(snap("s2", "Ponto 1")),
            operation = ReconcileOperation.REIMPORT_APPEND,
            now = 20L,
        )
        val result = report.decisions.single()
        assertEquals(SectionLink.ORPHANED, result.sectionLink)
        assertEquals(null, result.candidateSectionId)
        assertEquals("s1", result.sectionId)
    }

    @Test
    fun aplicacaoFicaStaleQuandoConteudoDaMesmaSecaoMuda() {
        val d = decision(application = ApplicationState.APPLIED, appliedHash = "h-s1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", "Ponto 1", 1, "h-s1")),
            newSections = listOf(snap("s1", "Ponto 1", 1, "h-s2")),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )
        val result = report.decisions.single()
        assertEquals(SectionLink.VALID, result.sectionLink)
        assertEquals(ApplicationState.STALE, result.applicationState)
        assertEquals(d.appliedHash, result.appliedHash)
    }

    @Test
    fun conteudoIgualMantemAplicacao() {
        val d = decision(application = ApplicationState.APPLIED, appliedHash = "h-s1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", "Ponto 1", 1, "h-s1")),
            newSections = listOf(snap("s1", "Ponto 1", 1, "h-s1")),
            operation = ReconcileOperation.UNDO_REMOVE,
            now = 20L,
        )
        assertEquals(ApplicationState.APPLIED, report.decisions.single().applicationState)
    }
}
