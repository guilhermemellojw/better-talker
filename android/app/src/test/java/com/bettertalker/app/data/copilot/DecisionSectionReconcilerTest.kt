package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.1A1 — reconciliação determinística e conservadora. */
class DecisionSectionReconcilerTest {

    private fun snap(
        id: String = "s1",
        title: String = "Ponto 1",
        order: Int = 1,
        hash: String = "h-s1",
        noteId: String = "n1",
    ) = SectionSnapshot(
        id = id,
        noteId = noteId,
        role = SectionRole.BODY,
        title = title,
        order = order,
        contentHash = hash,
    )

    private fun decision(
        id: String = "d1",
        noteId: String = "n1",
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
        noteId = noteId,
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
    fun mesmoIdEmOutraNotaNaoCriaAssociacaoCruzada() {
        val d = decision(sectionId = "s1", application = ApplicationState.APPLIED)
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", noteId = "n2", hash = "h-n2")),
            newSections = listOf(snap("s1", noteId = "n2", hash = "h-n2")),
            operation = ReconcileOperation.DELETE,
            now = 20L,
        )
        val result = report.decisions.single()
        assertEquals(d, result)
        assertEquals("n1", result.noteId)
        assertEquals("s1", result.sectionId)
        assertEquals(ApplicationState.APPLIED, result.applicationState)
    }

    @Test
    fun operacaoDeOutraNotaNaoAlteraDecisao() {
        val d = decision(sectionId = "s1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", noteId = "n2")),
            newSections = emptyList(),
            operation = ReconcileOperation.DELETE,
            now = 20L,
        )
        assertEquals(d, report.decisions.single())
        assertEquals(DecisionState.ACTIVE, report.decisions.single().decisionState)
    }

    @Test
    fun reimportReplaceDeOutraNotaNaoSugereCandidatoCruzado() {
        val d = decision(sectionId = "s1", title = "Ponto 1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", noteId = "n2")),
            newSections = listOf(snap("s2", "Ponto 1", 1, "h-s2", noteId = "n2")),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        )
        assertEquals(d, report.decisions.single())
        assertEquals(null, report.decisions.single().candidateSectionId)
    }

    @Test
    fun mesmoIdEmDuasNotasUsaAChaveCompostaCorreta() {
        val d = decision(sectionId = "s1", application = ApplicationState.APPLIED)
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap("s1", hash = "h-s1"), snap("s1", noteId = "n2", hash = "h-n2")),
            newSections = listOf(
                snap("s1", noteId = "n2", hash = "h-n2"),
                snap("s1", hash = "h-s1"),
            ),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )
        val result = report.decisions.single()
        assertEquals(ApplicationState.APPLIED, result.applicationState)
        assertEquals("n1", result.noteId)
    }

    @Test
    fun reordenacaoComMesmoIdMantemAssociacaoEAtualizaSnapshot() {
        val d = decision(order = 1)
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap(order = 1)),
            newSections = listOf(snap(order = 4)),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )
        val result = report.decisions.single()
        assertEquals("s1", result.sectionId)
        assertEquals(SectionLink.VALID, result.sectionLink)
        assertEquals(4, result.sectionOrderSnapshot)
        assertEquals(1, result.originalSectionOrderSnapshot)
        assertEquals(20L, result.updatedAt)
    }

    @Test
    fun renomeacaoComMesmoIdMantemAssociacaoEAtualizaSnapshot() {
        val d = decision(title = "Ponto 1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap(title = "Ponto 1")),
            newSections = listOf(snap(title = "Ponto 1 — revisado")),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        )
        val result = report.decisions.single()
        assertEquals("s1", result.sectionId)
        assertEquals(SectionLink.VALID, result.sectionLink)
        assertEquals("Ponto 1 — revisado", result.sectionTitleSnapshot)
        assertEquals("Ponto 1", result.originalSectionTitleSnapshot)
        assertEquals(20L, result.updatedAt)
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
    fun segundaImportacaoUsaSnapshotsDaUltimaAssociacaoConfirmada() {
        val pending = decision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )
        val remapped = DecisionTransitions.confirmRemap(
            pending,
            snap("s2", "Ponto 2", 2, "h-s2"),
            now = 20L,
        )
        assertEquals("Ponto 2", remapped.sectionTitleSnapshot)
        assertEquals(2, remapped.sectionOrderSnapshot)
        assertEquals("Ponto 1", remapped.originalSectionTitleSnapshot)
        assertEquals(1, remapped.originalSectionOrderSnapshot)

        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(remapped),
            oldSections = listOf(snap("s2", "Ponto 2", 2, "h-s2")),
            newSections = listOf(
                // Isca com os dados antigos de A.
                snap("s4", "Ponto 1", 1, "h-s4"),
                // A nova seção que corresponde ao snapshot atual de B.
                snap("s3", "Ponto 2", 2, "h-s3"),
            ),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 30L,
        )
        val result = report.decisions.single()
        assertEquals("s3", result.candidateSectionId)
        assertEquals("s2", result.sectionId)
        assertEquals("s1", result.originalSectionId)
        assertEquals(SectionLink.PENDING_RECONFIRMATION, result.sectionLink)
    }

    @Test
    fun renomeacaoAtualizaSnapshotAtualAntesDaProximaReimportacao() {
        val initial = decision(title = "Ponto A", order = 1)
        val renamed = DecisionSectionReconciler.reconcile(
            decisions = listOf(initial),
            oldSections = listOf(snap("s1", "Ponto A", 1, "h-a")),
            newSections = listOf(snap("s1", "Ponto B", 2, "h-a")),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        ).decisions.single()
        assertEquals("s1", renamed.sectionId)
        assertEquals(SectionLink.VALID, renamed.sectionLink)
        assertEquals("Ponto B", renamed.sectionTitleSnapshot)
        assertEquals(2, renamed.sectionOrderSnapshot)
        assertEquals("Ponto A", renamed.originalSectionTitleSnapshot)
        assertEquals(1, renamed.originalSectionOrderSnapshot)

        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(renamed),
            oldSections = listOf(snap("s1", "Ponto B", 2, "h-a")),
            newSections = listOf(
                snap("s3", "Ponto A", 1, "h-a"),
                snap("s2", "Ponto B", 2, "h-a"),
            ),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 30L,
        )
        val result = report.decisions.single()
        assertEquals("s2", result.candidateSectionId)
        assertEquals("s1", result.sectionId)
        assertEquals("s1", result.originalSectionId)
        assertEquals(SectionLink.PENDING_RECONFIRMATION, result.sectionLink)
    }

    @Test
    fun reconciliacaoIdempotenteNaoAtualizaTimestamps() {
        val orphan = DecisionSectionReconciler.reconcile(
            decisions = listOf(decision(link = SectionLink.ORPHANED)),
            oldSections = listOf(snap()),
            newSections = emptyList(),
            operation = ReconcileOperation.REMOVE,
            now = 20L,
        ).decisions.single()
        assertEquals(10L, orphan.updatedAt)

        val valid = DecisionSectionReconciler.reconcile(
            decisions = listOf(decision()),
            oldSections = listOf(snap()),
            newSections = listOf(snap()),
            operation = ReconcileOperation.REORDER,
            now = 20L,
        ).decisions.single()
        assertEquals(10L, valid.updatedAt)

        val pending = decision(
            link = SectionLink.PENDING_RECONFIRMATION,
            candidateSectionId = "s2",
        )
        val pendingAgain = DecisionSectionReconciler.reconcile(
            decisions = listOf(pending),
            oldSections = listOf(snap("s1", "Ponto 1", 1, "h-s1")),
            newSections = listOf(snap("s2", "Ponto 1", 1, "h-s2")),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
        ).decisions.single()
        assertEquals(10L, pendingAgain.updatedAt)
        assertEquals(pending, pendingAgain)

        val removed = decision(state = DecisionState.REMOVED)
        val removedAgain = DecisionSectionReconciler.reconcile(
            decisions = listOf(removed),
            oldSections = emptyList(),
            newSections = listOf(snap()),
            operation = ReconcileOperation.UNDO_REMOVE,
            now = 20L,
        ).decisions.single()
        assertEquals(removed, removedAgain)
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
        assertEquals("h-s2", result.sectionContentHashSnapshot)
        assertEquals("h-s1", result.originalSectionContentHashSnapshot)
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

    @Test
    fun reimportReplaceComOldNaoVazioENewVazioOrfanaDecisao() {
        val d = decision(application = ApplicationState.APPLIED, appliedHash = "h-s1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = listOf(snap()),
            newSections = emptyList(),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
            affectedNoteId = "n1",
        )
        val result = report.decisions.single()
        assertEquals(SectionLink.ORPHANED, result.sectionLink)
        assertEquals("s1", result.sectionId)
        assertNull(result.candidateSectionId)
        // Aplicação histórica não é apagada por remoção reversível.
        assertEquals(ApplicationState.APPLIED, result.applicationState)
        assertEquals(20L, result.updatedAt)
        assertTrue(report.orphaned.contains(result))
    }

    @Test
    fun reimportReplaceSemNenhumSnapshotNaoMantemValid() {
        val d = decision()
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d),
            oldSections = emptyList(),
            newSections = emptyList(),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
            affectedNoteId = "n1",
        )
        val result = report.decisions.single()
        assertEquals(SectionLink.ORPHANED, result.sectionLink)
        assertEquals("s1", result.sectionId)
        assertNull(result.candidateSectionId)
        assertEquals(20L, result.updatedAt)
    }

    @Test
    fun remocaoSemSnapshotsOrfanaDecisao() {
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(decision()),
            oldSections = emptyList(),
            newSections = emptyList(),
            operation = ReconcileOperation.REMOVE,
            now = 20L,
            affectedNoteId = "n1",
        )
        assertEquals(SectionLink.ORPHANED, report.decisions.single().sectionLink)
    }

    @Test
    fun deleteSemSnapshotsRemoveDecisaoLogicamente() {
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(decision()),
            oldSections = emptyList(),
            newSections = emptyList(),
            operation = ReconcileOperation.DELETE,
            now = 20L,
            affectedNoteId = "n1",
        )
        val result = report.decisions.single()
        assertEquals(DecisionState.REMOVED, result.decisionState)
        assertEquals(20L, result.removedAt)
        assertTrue(report.removed.contains(result))
    }

    @Test
    fun undoRemoveReassociaQuandoSecaoReaparece() {
        val orphan = decision(link = SectionLink.ORPHANED)
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(orphan),
            oldSections = emptyList(),
            newSections = listOf(snap()),
            operation = ReconcileOperation.UNDO_REMOVE,
            now = 30L,
            affectedNoteId = "n1",
        )
        val result = report.decisions.single()
        assertEquals(SectionLink.VALID, result.sectionLink)
        assertEquals("s1", result.sectionId)
        assertEquals("s1", result.originalSectionId)
        assertEquals(30L, result.updatedAt)
    }

    @Test
    fun reimportReplaceReassociaMesmoIdSemRemaperar() {
        val orphan = decision(link = SectionLink.ORPHANED)
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(orphan),
            oldSections = emptyList(),
            newSections = listOf(snap("s1", "Ponto 2", 2, "h-s2")),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 30L,
            affectedNoteId = "n1",
        )
        val result = report.decisions.single()
        assertEquals(SectionLink.VALID, result.sectionLink)
        assertEquals("s1", result.sectionId)
        assertEquals("Ponto 2", result.sectionTitleSnapshot)
        assertEquals("Ponto 1", result.originalSectionTitleSnapshot)
        assertEquals(2, result.sectionOrderSnapshot)
        assertEquals(1, result.originalSectionOrderSnapshot)
        assertEquals(30L, result.updatedAt)
    }

    @Test
    fun affectedNoteIdNaoTocaDecisaoDeOutraNotaComMesmoSectionId() {
        val d1 = decision(id = "d1", noteId = "n1", sectionId = "s1")
        val d2 = decision(id = "d2", noteId = "n2", sectionId = "s1")
        val report = DecisionSectionReconciler.reconcile(
            decisions = listOf(d1, d2),
            oldSections = emptyList(),
            newSections = emptyList(),
            operation = ReconcileOperation.REIMPORT_REPLACE,
            now = 20L,
            affectedNoteId = "n1",
        )
        assertEquals(SectionLink.ORPHANED, report.decisions[0].sectionLink)
        assertEquals(d2, report.decisions[1])
        assertEquals(SectionLink.VALID, report.decisions[1].sectionLink)
    }
}
