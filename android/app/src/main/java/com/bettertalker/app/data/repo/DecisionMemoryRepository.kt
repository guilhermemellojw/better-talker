package com.bettertalker.app.data.repo

import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionSectionReconciler
import com.bettertalker.app.data.copilot.DecisionTransitions
import com.bettertalker.app.data.copilot.ReconcileOperation
import com.bettertalker.app.data.copilot.ReconcileReport
import com.bettertalker.app.data.copilot.SectionSnapshot
import com.bettertalker.app.data.copilot.SupersededPair
import com.bettertalker.app.data.db.DecisionDao
import com.bettertalker.app.data.db.DecisionEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity

/**
 * 1C.2.1A2 — persistência transacional da memória de decisões.
 *
 * Regras de domínio NÃO são duplicadas aqui: remapeamento, órfã, remoção
 * terminal e supersessão delegam para [DecisionTransitions]; a reconciliação
 * estrutural delega para [DecisionSectionReconciler].
 *
 * Garantias de persistência:
 * - [save]/[saveAll] preservam createdAt, updatedAt, snapshots e demais
 *   campos exatamente como recebidos; repetir sem mudança não escreve.
 * - identidade (`id`, `noteId`, `scope`), `createdAt` e os snapshots
 *   originais não podem ser trocados por [save]/[saveAll]. [replaceForNote]
 *   é a substituição explícita, usada apenas quando o chamador quer trocar o
 *   conjunto inteiro da nota.
 * - operações multi-linha rodam dentro de [TransactionRunner]: ou todas
 *   gravam, ou nenhuma.
 * - [reconcile] grava somente os registros que mudaram; timestamps de
 *   registros inalterados não são tocados.
 */
class DecisionMemoryRepository(
    private val dao: DecisionDao,
    private val transactionRunner: TransactionRunner,
) {

    suspend fun get(id: String): DecisionRecord? {
        require(id.isNotBlank()) { "decision.id vazio" }
        return dao.get(id)?.toDomain()
    }

    suspend fun forNote(noteId: String): List<DecisionRecord> {
        require(noteId.isNotBlank()) { "noteId vazio" }
        return dao.forNote(noteId).map { it.toDomain() }
    }

    /** Upsert idempotente e sem recalcular timestamps/snapshots. */
    suspend fun save(decision: DecisionRecord) {
        val entity = decision.toEntity()
        transactionRunner.run {
            val existing = dao.get(entity.id)
            if (existing == entity) return@run
            requireSameIdentity(existing, entity)
            dao.upsert(entity)
        }
    }

    /** Lote atômico; cada linha segue as mesmas garantias de [save]. */
    suspend fun saveAll(decisions: List<DecisionRecord>) {
        if (decisions.isEmpty()) return
        requireUniqueIds(decisions.map { it.id })
        val entities = decisions.map { it.toEntity() }
        transactionRunner.run {
            val changed = mutableListOf<DecisionEntity>()
            for (entity in entities) {
                val existing = dao.get(entity.id)
                if (existing == entity) continue
                requireSameIdentity(existing, entity)
                changed += entity
            }
            if (changed.isNotEmpty()) dao.upsertAll(changed)
        }
    }

    /**
     * Substituição integral e atômica das decisões de uma nota. Diferente de
     * [saveAll], pode remover linhas e trocar timestamps: é uma operação
     * explícita de substituição, não uma evolução de registro.
     */
    suspend fun replaceForNote(noteId: String, decisions: List<DecisionRecord>) {
        require(noteId.isNotBlank()) { "noteId vazio" }
        require(decisions.all { it.noteId == noteId }) {
            "replaceForNote não pode receber decisão de outra nota"
        }
        requireUniqueIds(decisions.map { it.id })
        transactionRunner.run {
            // `DecisionEntity.id` é PK global. Antes de apagar a nota,
            // detecta IDs que já pertencem a OUTRA nota; nesse caso a
            // operação inteira falha, sem transferir nem sobrescrever nada.
            val entities = decisions.map { it.toEntity() }
            val collisions = entities.mapNotNull { entity ->
                val existing = dao.get(entity.id)
                entity.id.takeIf { existing != null && existing.noteId != noteId }
            }
            require(collisions.isEmpty()) {
                "replaceForNote colide com decisão de outra nota: " +
                    collisions.joinToString()
            }
            dao.deleteForNote(noteId)
            if (entities.isNotEmpty()) dao.upsertAll(entities)
        }
    }

    /**
     * Reconcilia somente as decisões de [noteId] e persiste as mudanças em uma
     * transação. As seções informadas precisam pertencer à mesma nota; o
     * reconciliador puro continua sendo a autoridade das regras.
     */
    suspend fun reconcile(
        noteId: String,
        oldSections: List<SectionSnapshot>,
        newSections: List<SectionSnapshot>,
        operation: ReconcileOperation,
        now: Long,
    ): ReconcileReport {
        require(noteId.isNotBlank()) { "noteId vazio" }
        require(oldSections.all { it.noteId == noteId }) {
            "oldSections contém seção de outra nota"
        }
        require(newSections.all { it.noteId == noteId }) {
            "newSections contém seção de outra nota"
        }
        return transactionRunner.run {
            val current = dao.forNote(noteId).map { it.toDomain() }
            val report = DecisionSectionReconciler.reconcile(
                decisions = current,
                oldSections = oldSections,
                newSections = newSections,
                operation = operation,
                now = now,
                affectedNoteId = noteId,
            )
            val changed = report.decisions.filterIndexed { index, record ->
                record != current[index]
            }
            if (changed.isNotEmpty()) dao.upsertAll(changed.map { it.toEntity() })
            report
        }
    }

    /** Confirma o candidato sugerido; candidato divergente falha no domínio. */
    suspend fun confirmRemap(
        decisionId: String,
        target: SectionSnapshot,
        now: Long,
    ): DecisionRecord? = update(decisionId) {
        DecisionTransitions.confirmRemap(it, target, now)
    }

    /** Operação explícita separada para escolher outro destino. */
    suspend fun confirmManualRemap(
        decisionId: String,
        target: SectionSnapshot,
        now: Long,
    ): DecisionRecord? = update(decisionId) {
        DecisionTransitions.confirmManualRemap(it, target, now)
    }

    suspend fun orphanForReversibleRemoval(
        decisionId: String,
        now: Long,
    ): DecisionRecord? = update(decisionId) {
        DecisionTransitions.orphanForReversibleRemoval(it, now)
    }

    suspend fun removePermanently(
        decisionId: String,
        now: Long,
    ): DecisionRecord? = update(decisionId) {
        DecisionTransitions.removePermanently(it, now)
    }

    /**
     * Persiste anterior + substituta no mesmo commit, usando a validação e as
     * transições do domínio. O histórico anterior é preservado como
     * SUPERSEDED, nunca apagado.
     */
    suspend fun supersede(
        previousId: String,
        replacement: DecisionRecord,
        now: Long,
    ): SupersededPair? {
        require(previousId.isNotBlank()) { "previousId vazio" }
        return transactionRunner.run {
            val previous = dao.get(previousId)?.toDomain() ?: return@run null
            val pair = DecisionTransitions.supersede(previous, replacement, now)
            require(dao.get(pair.replacement.id) == null) {
                "replacement ${pair.replacement.id} já existe"
            }
            dao.upsertAll(
                listOf(
                    pair.previous.toEntity(),
                    pair.replacement.toEntity(),
                ),
            )
            pair
        }
    }

    private suspend fun update(
        decisionId: String,
        transition: (DecisionRecord) -> DecisionRecord,
    ): DecisionRecord? {
        require(decisionId.isNotBlank()) { "decision.id vazio" }
        return transactionRunner.run {
            val current = dao.get(decisionId)?.toDomain() ?: return@run null
            val updated = transition(current)
            require(updated.updatedAt >= current.updatedAt) {
                "transição não pode regredir updatedAt"
            }
            if (updated != current) dao.upsert(updated.toEntity())
            updated
        }
    }

    /**
     * Identidade e história original são imutáveis por evolução de registro.
     * A troca de assunto/nota exige operação explícita fora desta camada.
     */
    private fun requireSameIdentity(existing: DecisionEntity?, incoming: DecisionEntity) {
        if (existing == null) return
        require(incoming.noteId == existing.noteId) {
            "noteId de ${incoming.id} é imutável"
        }
        require(incoming.scope == existing.scope) {
            "scope de ${incoming.id} é imutável"
        }
        require(incoming.createdAt == existing.createdAt) {
            "createdAt de ${incoming.id} é imutável"
        }
        require(incoming.updatedAt >= existing.updatedAt) {
            "save não pode regredir updatedAt de ${incoming.id}"
        }
        require(
            incoming.originalSectionId == existing.originalSectionId &&
                incoming.originalSectionTitleSnapshot == existing.originalSectionTitleSnapshot &&
                incoming.originalSectionRoleSnapshot == existing.originalSectionRoleSnapshot &&
                incoming.originalSectionOrderSnapshot == existing.originalSectionOrderSnapshot &&
                incoming.originalSectionContentHashSnapshot ==
                existing.originalSectionContentHashSnapshot
        ) {
            "snapshot original de ${incoming.id} é imutável"
        }
    }

    private fun requireUniqueIds(ids: List<String>) {
        require(ids.distinct().size == ids.size) {
            "lista contém decisões com id repetido"
        }
    }
}
