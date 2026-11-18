package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.normalizeText

/** Operação estrutural que pode afetar a associação de uma decisão. */
enum class ReconcileOperation {
    REORDER,
    ADD,
    REMOVE,
    UNDO_REMOVE,
    REIMPORT_APPEND,
    REIMPORT_REPLACE,
    DELETE,
}

/**
 * Resultado determinístico da reconciliação.
 *
 * `decisions` preserva a ordem de entrada; as listas classificam os registros
 * resultantes para UI/testes. Nenhuma lista é usada para remapear sozinha.
 */
data class ReconcileReport(
    val decisions: List<DecisionRecord>,
    val unchanged: List<DecisionRecord>,
    val pendingReconfirmation: List<DecisionRecord>,
    val orphaned: List<DecisionRecord>,
    val removed: List<DecisionRecord>,
    val warnings: List<String> = emptyList(),
)

/**
 * 1C.2.1A1 — reconciliação pura de decisões com seções.
 *
 * Regra central: somente a preservação do ID mantém a associação
 * automaticamente. Título, ordem e similaridade podem gerar apenas uma
 * sugestão de candidato (`PENDING_RECONFIRMATION`), nunca um remapeamento.
 *
 * Não lê nem escreve Room; não altera notas/seções.
 */
object DecisionSectionReconciler {

    /**
     * [affectedNoteId] identifica a nota da operação quando
     * `oldSections`/`newSections` não carregam snapshots (ex.: reimportação
     * de uma nota que ficou sem seções). Sem isso, listas vazias não dizem
     * qual nota foi afetada e a decisão poderia permanecer falsamente VALID.
     * O parâmetro não remapeia nada: apenas permite reconciliar a ausência de
     * seção. O default preserva chamadas antigas.
     */
    fun reconcile(
        decisions: List<DecisionRecord>,
        oldSections: List<SectionSnapshot>,
        newSections: List<SectionSnapshot>,
        operation: ReconcileOperation,
        now: Long,
        affectedNoteId: String? = null,
    ): ReconcileReport {
        // A identidade é composta: o mesmo sectionId em outra nota NUNCA é a
        // mesma seção. Operações de uma nota não alteram decisões de outra.
        val oldByKey = oldSections.associateBy { it.noteId to it.id }
        val newByKey = newSections.associateBy { it.noteId to it.id }
        val affectedNotes = buildSet {
            oldSections.forEach { add(it.noteId) }
            newSections.forEach { add(it.noteId) }
            affectedNoteId?.let {
                require(it.isNotBlank()) { "affectedNoteId vazio" }
                add(it)
            }
        }
        val warnings = mutableListOf<String>()

        val updated = decisions.map { decision ->
            if (decision.scope == DecisionScope.NOTE) {
                decision
            } else if (decision.decisionState == DecisionState.REMOVED) {
                // REMOVED é terminal; nem uma seção reaparecida o ressuscita.
                decision
            } else if (decision.noteId !in affectedNotes) {
                // Operação de outra nota não pode tocar nesta decisão.
                decision
            } else {
                val current = decision.sectionId?.let {
                    newByKey[decision.noteId to it]
                }
                if (current != null) {
                    reconcileExact(decision, current, now)
                } else {
                    val oldKey = decision.sectionId?.let { decision.noteId to it }
                    if (oldKey == null || oldKey !in oldByKey) {
                        warnings += "decisão ${decision.id} já não tinha seção conhecida"
                    }
                    reconcileMissing(decision, newSections, operation, now, warnings)
                }
            }
        }

        val unchanged = updated.filterIndexed { index, record -> record == decisions[index] }
        return ReconcileReport(
            decisions = updated,
            unchanged = unchanged,
            pendingReconfirmation = updated.filter {
                it.sectionLink == SectionLink.PENDING_RECONFIRMATION
            },
            orphaned = updated.filter { it.sectionLink == SectionLink.ORPHANED },
            removed = updated.filter { it.decisionState == DecisionState.REMOVED },
            warnings = warnings,
        )
    }

    /** ID preservado: a associação continua; aplicação pode ficar stale. */
    private fun reconcileExact(
        decision: DecisionRecord,
        section: SectionSnapshot,
        now: Long,
    ): DecisionRecord {
        // A associação é mantida pelo ID composto, mas o snapshot corrente
        // precisa refletir a seção atual (título/ordem/papel/hash). O snapshot
        // original permanece intocado para o histórico.
        var result = decision.copy(
            sectionTitleSnapshot = section.title,
            sectionRoleSnapshot = section.role,
            sectionOrderSnapshot = section.order,
            sectionContentHashSnapshot = section.contentHash,
        )
        if (result.sectionLink != SectionLink.VALID) {
            result = result.copy(
                sectionLink = SectionLink.VALID,
                candidateSectionId = null,
            )
        }
        // Aplicação histórica continua aplicada se o conteúdo for o mesmo.
        // Se o texto da seção mudou, a aplicação registrada fica desatualizada.
        if (
            result.applicationState == ApplicationState.APPLIED &&
            result.appliedHash != section.contentHash
        ) {
            result = result.copy(applicationState = ApplicationState.STALE)
        }
        return if (result != decision) {
            result.copy(updatedAt = maxOf(now, decision.updatedAt))
        } else {
            decision
        }
    }

    /** ID desapareceu: nunca transferir automaticamente. */
    private fun reconcileMissing(
        decision: DecisionRecord,
        newSections: List<SectionSnapshot>,
        operation: ReconcileOperation,
        now: Long,
        warnings: MutableList<String>,
    ): DecisionRecord = when (operation) {
        ReconcileOperation.DELETE -> {
            // Só a decisão ativa vira REMOVED. SUPERSEDED/RESOLVED preservam
            // seu histórico; REMOVED já foi filtrado pelo chamador.
            if (decision.decisionState == DecisionState.ACTIVE) {
                DecisionTransitions.removePermanently(decision, now)
            } else {
                decision
            }
        }

        ReconcileOperation.REMOVE,
        ReconcileOperation.UNDO_REMOVE,
        ReconcileOperation.REORDER,
        ReconcileOperation.ADD,
        ReconcileOperation.REIMPORT_APPEND ->
            DecisionTransitions.orphanForReversibleRemoval(decision, now)

        ReconcileOperation.REIMPORT_REPLACE -> {
            val candidate = candidateFor(decision, newSections)
            if (candidate == null) {
                warnings += "decisão ${decision.id} órfã: sem candidato único"
                DecisionTransitions.orphanForReversibleRemoval(decision, now)
            } else {
                warnings +=
                    "decisão ${decision.id} sugere remapeamento para ${candidate.id}; " +
                        "confirmação necessária"
                DecisionTransitions.markPendingReconfirmation(decision, candidate.id, now)
            }
        }
    }

    /**
     * Sugestão conservadora:
     * 1) mesmo papel + título normalizado exato, se único;
     * 2) mesmo papel + mesma ordem, se único.
     *
     * Título/ordem NUNCA alteram `sectionId`; apenas `candidateSectionId`.
     */
    private fun candidateFor(
        decision: DecisionRecord,
        newSections: List<SectionSnapshot>,
    ): SectionSnapshot? {
        val role = decision.sectionRoleSnapshot ?: return null
        val title = decision.sectionTitleSnapshot ?: return null
        val sameRole = newSections.filter {
            it.noteId == decision.noteId && it.role == role && it.id != decision.sectionId
        }
        val normalizedTitle = normalizeText(title)
        val titleMatches = sameRole.filter { normalizeText(it.title) == normalizedTitle }
        if (titleMatches.size == 1) return titleMatches.single()
        if (titleMatches.size > 1) return null

        val order = decision.sectionOrderSnapshot ?: return null
        val orderMatches = sameRole.filter { it.order == order }
        return orderMatches.singleOrNull()
    }
}
