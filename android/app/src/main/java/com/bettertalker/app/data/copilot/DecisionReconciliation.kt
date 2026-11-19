package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.repo.DecisionMemoryRepository

/**
 * 1C.2.2A — seam entre persistência estrutural de seções e o ledger.
 *
 * A implementação de produção delega a [DecisionMemoryRepository.reconcile]
 * dentro da mesma transação Room da alteração estrutural. Testes podem usar
 * um fake para registrar os snapshots/operação sem tocar no banco.
 */
fun interface DecisionReconciliationHook {
    suspend fun onSectionsChanged(
        noteId: String,
        oldSections: List<SectionSnapshot>,
        newSections: List<SectionSnapshot>,
        operation: ReconcileOperation,
        now: Long,
    )
}

fun DecisionMemoryRepository.asReconciliationHook(): DecisionReconciliationHook =
    DecisionReconciliationHook { noteId, oldSections, newSections, operation, now ->
        reconcile(
            noteId = noteId,
            oldSections = oldSections,
            newSections = newSections,
            operation = operation,
            now = now,
        )
    }
