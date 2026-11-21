package com.bettertalker.app.data.repo

import com.bettertalker.app.data.copilot.AmbiguityReason
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionRelationship
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSectionReconciler
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.RemoveDecisionResult
import com.bettertalker.app.data.copilot.DecisionTransitions
import com.bettertalker.app.data.copilot.IneligibleReason
import com.bettertalker.app.data.copilot.ReconcileOperation
import com.bettertalker.app.data.copilot.ReconcileReport
import com.bettertalker.app.data.copilot.SectionSnapshot
import com.bettertalker.app.data.copilot.SupersededPair
import com.bettertalker.app.data.copilot.SupersessionAmbiguityReason
import com.bettertalker.app.data.copilot.SupersessionConflictReason
import com.bettertalker.app.data.copilot.SupersessionConfirmation
import com.bettertalker.app.data.copilot.SupersessionIneligibleReason
import com.bettertalker.app.data.copilot.SupersessionResult
import com.bettertalker.app.data.copilot.classifyDecisionRelationship
import com.bettertalker.app.data.copilot.hasOriginCollision
import com.bettertalker.app.data.copilot.hasStableOriginIdentity
import com.bettertalker.app.data.copilot.relationshipEligibilityFailure
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
     * 1C.2.2B1a — remoção condicional e atômica.
     *
     * Valida nota, kind e estado DENTRO da transação, evitando corrida entre
     * leitura e escrita. Retorna um resultado tipado que distingue sucesso
     * efetivo de registro ausente ou inelegível.
     */
    suspend fun removePermanentlyIfEligible(
        decisionId: String,
        noteId: String,
        now: Long = System.currentTimeMillis(),
    ): RemoveDecisionResult {
        require(decisionId.isNotBlank()) { "decision.id vazio" }
        require(noteId.isNotBlank()) { "noteId vazio" }
        return transactionRunner.run {
            val current = dao.get(decisionId)?.toDomain()
                ?: return@run RemoveDecisionResult.NotFound
            if (current.noteId != noteId) {
                return@run RemoveDecisionResult.Ineligible
            }
            if (current.kind != DecisionKind.CONFIRMED ||
                current.decisionState != DecisionState.ACTIVE
            ) {
                return@run RemoveDecisionResult.Ineligible
            }
            val updated = DecisionTransitions.removePermanently(current, now)
            if (updated != current) dao.upsert(updated.toEntity())
            RemoveDecisionResult.Removed(updated)
        }
    }

    /**
     * API LEGADA — mantida para compatibilidade com a B1 e testes existentes.
     *
     * Usa `upsertAll()` e NÃO oferece todas as garantias da B2B (confirmação
     * explícita, compare-and-set, inserção sem sobrescrita e resultados
     * tipados). A futura B2C NÃO deve chamá-la; substituições confirmadas
     * devem usar exclusivamente [supersedeConfirmed].
     *
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

    /**
     * 1C.2.1B2B — substituição EXPLICITAMENTE confirmada.
     *
     * A confirmação não vem de [classifyDecisionRelationship]; ela é um
     * contrato separado que a B2C só construirá após uma escolha humana.
     *
     * Garantias:
     * - uma transação Room cobre validação, inserção da substituta e
     *   compare-and-set da anterior (`ACTIVE` → `SUPERSEDED`);
     * - a anterior permanece no histórico, com snapshots e aplicação;
     * - a substituta aponta para a anterior por `supersedesId`;
     * - repetir a mesma operação não altera timestamps nem reescreve linhas;
     * - A→C vencendo a corrida impede A→B; nunca há duas substitutas aceitas.
     */
    suspend fun supersedeConfirmed(
        confirmation: SupersessionConfirmation,
        replacement: DecisionRecord,
        now: Long = System.currentTimeMillis(),
    ): SupersessionResult {
        if (
            confirmation.confirmationId.isBlank() ||
            confirmation.noteId.isBlank() ||
            confirmation.previousDecisionId.isBlank() ||
            confirmation.replacementDecisionId.isBlank() ||
            confirmation.previousDecisionId == confirmation.replacementDecisionId ||
            replacement.id != confirmation.replacementDecisionId
        ) {
            return SupersessionResult.Conflict(
                SupersessionConflictReason.CONFIRMATION_MISMATCH,
            )
        }
        if (confirmation.expectedPreviousState != DecisionState.ACTIVE) {
            return SupersessionResult.Ineligible(
                SupersessionIneligibleReason.INVALID_CONFIRMATION,
            )
        }
        if (replacement.noteId != confirmation.noteId) {
            return SupersessionResult.Ineligible(
                SupersessionIneligibleReason.DIFFERENT_NOTE,
            )
        }

        return try {
            transactionRunner.run {
                val previous = dao.get(confirmation.previousDecisionId)?.toDomain()
                    ?: return@run SupersessionResult.NotFound(
                        confirmation.previousDecisionId,
                    )
                if (previous.noteId != confirmation.noteId) {
                    return@run SupersessionResult.Conflict(
                        SupersessionConflictReason.CONFIRMATION_MISMATCH,
                    )
                }

                val persistedReplacement =
                    dao.get(confirmation.replacementDecisionId)?.toDomain()

                // O vínculo pedido pela substituta precisa ser compatível com
                // esta operação ANTES de qualquer atalho de idempotência.
                // Repetir A→B com B.supersedesId=C é conflito, não sucesso.
                if (
                    replacement.supersedesId != null &&
                    replacement.supersedesId != previous.id
                ) {
                    return@run SupersessionResult.Conflict(
                        SupersessionConflictReason.REPLACEMENT_ALREADY_LINKED,
                    )
                }

                // Idempotência: a operação A→B já foi persistida. Isso vence
                // qualquer stale do estado atual de A.
                if (
                    previous.decisionState == DecisionState.SUPERSEDED &&
                    persistedReplacement != null &&
                    persistedReplacement.supersedesId == previous.id
                ) {
                    return@run if (sameReplacementPayload(replacement, persistedReplacement)) {
                        SupersessionResult.AlreadyApplied(previous, persistedReplacement)
                    } else {
                        SupersessionResult.Conflict(
                            SupersessionConflictReason.REPLACEMENT_PAYLOAD_DIVERGED,
                        )
                    }
                }

                if (previous.decisionState != DecisionState.ACTIVE) {
                    return@run when (previous.decisionState) {
                        DecisionState.SUPERSEDED -> SupersessionResult.Conflict(
                            SupersessionConflictReason.PREVIOUS_SUPERSEDED_BY_OTHER,
                        )

                        else -> SupersessionResult.Ineligible(
                            SupersessionIneligibleReason.PREVIOUS_NOT_ACTIVE,
                        )
                    }
                }

                if (persistedReplacement != null) {
                    return@run SupersessionResult.Conflict(
                        SupersessionConflictReason.REPLACEMENT_ID_COLLISION,
                    )
                }

                val pair = when (
                    val validation = validateConfirmedPair(
                        confirmation = confirmation,
                        previous = previous,
                        replacement = replacement,
                        now = now,
                    )
                ) {
                    is PairValidation.Valid -> validation.pair
                    is PairValidation.Failed -> return@run validation.result
                }

                // Primeiro insere a substituta; a PK é a barreira contra
                // overwrite silencioso. Se a linha já existir, o -1 indica
                // corrida ou colisão.
                val inserted = dao.insertIfAbsent(pair.replacement.toEntity())
                if (inserted == -1L) {
                    val currentPrevious = dao.get(previous.id)?.toDomain()
                    val currentReplacement = dao.get(pair.replacement.id)?.toDomain()
                    return@run if (
                        currentPrevious != null &&
                        currentReplacement != null &&
                        isPersistedPair(
                            currentPrevious,
                            currentReplacement,
                            replacement,
                        )
                    ) {
                        SupersessionResult.AlreadyApplied(
                            currentPrevious,
                            currentReplacement,
                        )
                    } else {
                        SupersessionResult.Conflict(
                            SupersessionConflictReason.REPLACEMENT_ID_COLLISION,
                        )
                    }
                }

                // Compare-and-set: só a primeira transação que encontrar A
                // ACTIVE vence. Se perdemos, a substituta inserida acima é
                // desfeita pelo rollback da transação.
                val updated = dao.markSupersededIfActive(
                    id = previous.id,
                    updatedAt = pair.previous.updatedAt,
                )
                if (updated != 1) {
                    throw SupersessionRollback(
                        SupersessionResult.Conflict(
                            SupersessionConflictReason.CONCURRENT_SUPERSESSION,
                        ),
                    )
                }

                SupersessionResult.Committed(pair.previous, pair.replacement)
            }
        } catch (rollback: SupersessionRollback) {
            rollback.result
        }
    }

    /**
     * 1C.2.1B2C — variante para substituta JÁ PERSISTIDA pela B1.
     *
     * Distinta de [supersedeConfirmed]: aqui a substituta existe, então não há
     * insert; o vínculo é gravado por compare-and-set (`supersedesId IS NULL`
     * + versão esperada) e a anterior vira `SUPERSEDED` no mesmo commit.
     * Reutiliza exatamente as mesmas validações de [validateConfirmedPair].
     *
     * [expectedPrevious] e [expectedReplacement] são os retratos EXIBIDOS ao
     * usuário, não releituras feitas na confirmação. Qualquer mudança em
     * qualquer um deles (inclusive no mesmo milissegundo) impede a escrita:
     * A divergente retorna [SupersessionResult.Stale]; B divergente retorna
     * [SupersessionResult.ReplacementStale].
     */
    suspend fun supersedePersistedReplacementConfirmed(
        confirmation: SupersessionConfirmation,
        expectedPrevious: DecisionRecord,
        expectedReplacement: DecisionRecord,
        now: Long = System.currentTimeMillis(),
    ): SupersessionResult {
        if (
            confirmation.confirmationId.isBlank() ||
            confirmation.noteId.isBlank() ||
            confirmation.previousDecisionId.isBlank() ||
            confirmation.replacementDecisionId.isBlank() ||
            confirmation.previousDecisionId == confirmation.replacementDecisionId ||
            expectedPrevious.id != confirmation.previousDecisionId ||
            expectedPrevious.noteId != confirmation.noteId ||
            expectedReplacement.id != confirmation.replacementDecisionId ||
            expectedReplacement.noteId != confirmation.noteId
        ) {
            return SupersessionResult.Conflict(
                SupersessionConflictReason.CONFIRMATION_MISMATCH,
            )
        }
        if (confirmation.expectedPreviousState != DecisionState.ACTIVE) {
            return SupersessionResult.Ineligible(
                SupersessionIneligibleReason.INVALID_CONFIRMATION,
            )
        }

        return try {
            transactionRunner.run {
                val previous = dao.get(confirmation.previousDecisionId)?.toDomain()
                    ?: return@run SupersessionResult.NotFound(
                        confirmation.previousDecisionId,
                    )
                val replacement = dao.get(confirmation.replacementDecisionId)?.toDomain()
                    ?: return@run SupersessionResult.NotFound(
                        confirmation.replacementDecisionId,
                    )

                if (previous.noteId != confirmation.noteId) {
                    return@run SupersessionResult.Conflict(
                        SupersessionConflictReason.CONFIRMATION_MISMATCH,
                    )
                }

                // Par já vinculado: idempotência legítima.
                if (replacement.supersedesId == previous.id) {
                    return@run if (previous.decisionState == DecisionState.SUPERSEDED) {
                        SupersessionResult.AlreadyApplied(previous, replacement)
                    } else {
                        SupersessionResult.Conflict(
                            SupersessionConflictReason.REPLACEMENT_ALREADY_LINKED,
                        )
                    }
                }
                if (replacement.supersedesId != null) {
                    return@run SupersessionResult.Conflict(
                        SupersessionConflictReason.REPLACEMENT_ALREADY_LINKED,
                    )
                }

                if (previous.decisionState != DecisionState.ACTIVE) {
                    return@run when (previous.decisionState) {
                        DecisionState.SUPERSEDED -> SupersessionResult.Conflict(
                            SupersessionConflictReason.PREVIOUS_SUPERSEDED_BY_OTHER,
                        )

                        else -> SupersessionResult.Ineligible(
                            SupersessionIneligibleReason.PREVIOUS_NOT_ACTIVE,
                        )
                    }
                }

                // A decisão anterior exibida também faz parte da confirmação.
                // A igualdade integral cobre conteúdo, assunto, origem,
                // seção, snapshots, aplicação, vínculo e timestamps — inclusive
                // mudanças que preservam o mesmo `updatedAt`.
                if (previous != expectedPrevious) {
                    return@run SupersessionResult.Stale(
                        previousDecisionId = previous.id,
                        expectedUpdatedAt = expectedPrevious.updatedAt,
                        actualUpdatedAt = previous.updatedAt,
                    )
                }

                // A versão exibida da substituta faz parte da confirmação.
                // `updatedAt` cobre a versão; a igualdade integral cobre
                // conteúdo/identidade alterados no mesmo milissegundo.
                if (replacement != expectedReplacement) {
                    return@run SupersessionResult.ReplacementStale(
                        replacementDecisionId = replacement.id,
                        expectedUpdatedAt = expectedReplacement.updatedAt,
                        actualUpdatedAt = replacement.updatedAt,
                    )
                }

                val pair = when (
                    val validation = validateConfirmedPair(
                        confirmation = confirmation,
                        previous = previous,
                        replacement = replacement,
                        now = now,
                    )
                ) {
                    is PairValidation.Valid -> validation.pair
                    is PairValidation.Failed -> return@run validation.result
                }

                // Primeiro prende a substituta; se o vínculo já tiver sido
                // tomado ou a versão mudado, nada da anterior é alterado.
                val linked = dao.linkReplacementIfUnlinked(
                    replacementId = pair.replacement.id,
                    previousId = previous.id,
                    expectedUpdatedAt = expectedReplacement.updatedAt,
                    updatedAt = pair.replacement.updatedAt,
                )
                if (linked != 1) {
                    val currentPrevious = dao.get(previous.id)?.toDomain()
                    val currentReplacement = dao.get(replacement.id)?.toDomain()
                    return@run when {
                        currentPrevious != null &&
                            currentReplacement != null &&
                            currentPrevious.decisionState == DecisionState.SUPERSEDED &&
                            currentReplacement.supersedesId == currentPrevious.id ->
                            SupersessionResult.AlreadyApplied(
                                currentPrevious,
                                currentReplacement,
                            )

                        currentReplacement?.supersedesId != null &&
                            currentReplacement.supersedesId != previous.id ->
                            SupersessionResult.Conflict(
                                SupersessionConflictReason.REPLACEMENT_ALREADY_LINKED,
                            )

                        else -> SupersessionResult.Conflict(
                            SupersessionConflictReason.CONCURRENT_SUPERSESSION,
                        )
                    }
                }

                val updated = dao.markSupersededIfActive(
                    id = previous.id,
                    updatedAt = pair.previous.updatedAt,
                )
                if (updated != 1) {
                    throw SupersessionRollback(
                        SupersessionResult.Conflict(
                            SupersessionConflictReason.CONCURRENT_SUPERSESSION,
                        ),
                    )
                }

                SupersessionResult.Committed(pair.previous, pair.replacement)
            }
        } catch (rollback: SupersessionRollback) {
            rollback.result
        }
    }

    /**
     * Política única de validação compartilhada por [supersedeConfirmed] e
     * [supersedePersistedReplacementConfirmed]. Não grava nada.
     */
    private fun validateConfirmedPair(
        confirmation: SupersessionConfirmation,
        previous: DecisionRecord,
        replacement: DecisionRecord,
        now: Long,
    ): PairValidation {
        if (confirmation.noteId != previous.noteId) {
            return PairValidation.Failed(
                SupersessionResult.Conflict(
                    SupersessionConflictReason.CONFIRMATION_MISMATCH,
                ),
            )
        }
        if (replacement.noteId != previous.noteId) {
            return PairValidation.Failed(
                SupersessionResult.Ineligible(
                    SupersessionIneligibleReason.DIFFERENT_NOTE,
                ),
            )
        }

        previous.relationshipEligibilityFailure()?.let { reason ->
            return PairValidation.Failed(
                SupersessionResult.Ineligible(mapPreviousIneligibility(reason)),
            )
        }
        replacement.relationshipEligibilityFailure()?.let { reason ->
            return PairValidation.Failed(
                SupersessionResult.Ineligible(mapReplacementIneligibility(reason)),
            )
        }

        if (previous.scope != replacement.scope) {
            return PairValidation.Failed(
                SupersessionResult.Ineligible(
                    SupersessionIneligibleReason.DIFFERENT_SCOPE,
                ),
            )
        }
        if (previous.scope == DecisionScope.SECTION) {
            if (previous.sectionId != replacement.sectionId) {
                return PairValidation.Failed(
                    SupersessionResult.Ineligible(
                        SupersessionIneligibleReason.DIFFERENT_SECTION,
                    ),
                )
            }
            if (!DecisionTransitions.allowsRemappedOrigin(previous, replacement)) {
                return PairValidation.Failed(
                    SupersessionResult.Ineligible(
                        SupersessionIneligibleReason.DIFFERENT_ORIGINAL_SECTION,
                    ),
                )
            }
        }

        if (previous.updatedAt != confirmation.expectedPreviousUpdatedAt) {
            return PairValidation.Failed(
                SupersessionResult.Stale(
                    previousDecisionId = previous.id,
                    expectedUpdatedAt = confirmation.expectedPreviousUpdatedAt,
                    actualUpdatedAt = previous.updatedAt,
                ),
            )
        }

        if (
            !previous.hasStableOriginIdentity() ||
            !replacement.hasStableOriginIdentity()
        ) {
            return PairValidation.Failed(
                SupersessionResult.Ambiguous(
                    SupersessionAmbiguityReason.MISSING_STABLE_ORIGIN,
                ),
            )
        }
        if (hasOriginCollision(previous, replacement)) {
            return PairValidation.Failed(
                SupersessionResult.Conflict(
                    SupersessionConflictReason.ORIGIN_COLLISION,
                ),
            )
        }
        if (
            replacement.supersedesId != null &&
            replacement.supersedesId != previous.id
        ) {
            return PairValidation.Failed(
                SupersessionResult.Conflict(
                    SupersessionConflictReason.REPLACEMENT_ALREADY_LINKED,
                ),
            )
        }

        when (
            val relationship = classifyDecisionRelationship(
                incoming = replacement,
                existing = listOf(previous),
            )
        ) {
            is DecisionRelationship.PotentialOverlap,
            is DecisionRelationship.LegacyPotentialOverlap -> Unit

            is DecisionRelationship.Ambiguous -> return PairValidation.Failed(
                SupersessionResult.Ambiguous(mapAmbiguity(relationship.reason)),
            )

            is DecisionRelationship.Ineligible -> return PairValidation.Failed(
                SupersessionResult.Ineligible(
                    SupersessionIneligibleReason.RELATIONSHIP_NOT_ELIGIBLE,
                ),
            )

            is DecisionRelationship.Independent -> return PairValidation.Failed(
                SupersessionResult.Ineligible(
                    SupersessionIneligibleReason.DIFFERENT_SUBJECT,
                ),
            )

            is DecisionRelationship.SameEvent -> return PairValidation.Failed(
                SupersessionResult.Conflict(
                    SupersessionConflictReason.CONFIRMATION_MISMATCH,
                ),
            )
        }

        val linked = replacement.copy(
            supersedesId = previous.id,
            updatedAt = maxOf(now, replacement.updatedAt),
        )
        val pair = try {
            DecisionTransitions.supersede(
                previous = previous,
                replacement = linked,
                now = now,
                allowRemappedPrevious = true,
            )
        } catch (_: IllegalArgumentException) {
            return PairValidation.Failed(
                SupersessionResult.Ineligible(
                    SupersessionIneligibleReason.INVALID_TRANSITION,
                ),
            )
        }
        return PairValidation.Valid(pair)
    }

    private fun mapPreviousIneligibility(
        reason: IneligibleReason,
    ): SupersessionIneligibleReason = when (reason) {
        IneligibleReason.NOT_ACTIVE -> SupersessionIneligibleReason.PREVIOUS_NOT_ACTIVE
        IneligibleReason.NOT_CONFIRMED -> SupersessionIneligibleReason.NOT_CONFIRMED
        IneligibleReason.SECTION_LINK_NOT_VALID -> SupersessionIneligibleReason.INVALID_SECTION_LINK
        IneligibleReason.SUBJECT_SCOPE_MISMATCH ->
            SupersessionIneligibleReason.RELATIONSHIP_NOT_ELIGIBLE
    }

    private fun mapReplacementIneligibility(
        reason: IneligibleReason,
    ): SupersessionIneligibleReason = when (reason) {
        IneligibleReason.NOT_ACTIVE -> SupersessionIneligibleReason.REPLACEMENT_NOT_ACTIVE
        IneligibleReason.NOT_CONFIRMED -> SupersessionIneligibleReason.NOT_CONFIRMED
        IneligibleReason.SECTION_LINK_NOT_VALID -> SupersessionIneligibleReason.INVALID_SECTION_LINK
        IneligibleReason.SUBJECT_SCOPE_MISMATCH ->
            SupersessionIneligibleReason.RELATIONSHIP_NOT_ELIGIBLE
    }

    private fun mapAmbiguity(
        reason: AmbiguityReason,
    ): SupersessionAmbiguityReason = when (reason) {
        AmbiguityReason.MULTIPLE_CANDIDATES ->
            SupersessionAmbiguityReason.RELATIONSHIP_AMBIGUOUS
        AmbiguityReason.UNKNOWN_SUBJECT -> SupersessionAmbiguityReason.UNKNOWN_SUBJECT
        AmbiguityReason.IDENTITY_COLLISION -> SupersessionAmbiguityReason.IDENTITY_AMBIGUOUS
    }

    /**
     * Conteúdo/identidade da substituta, ignorando metadados mutáveis da
     * transição e do ciclo de vida (`updatedAt`, `decisionState`, `sectionId`
     * atual, `sectionLink`, `applicationState`, snapshots atuais).
     */
    private fun sameReplacementPayload(
        requested: DecisionRecord,
        persisted: DecisionRecord,
    ): Boolean =
        requested.id == persisted.id &&
            requested.noteId == persisted.noteId &&
            requested.scope == persisted.scope &&
            requested.kind == persisted.kind &&
            requested.text == persisted.text &&
            requested.source == persisted.source &&
            requested.originalSectionId == persisted.originalSectionId &&
            requested.originalSectionTitleSnapshot ==
            persisted.originalSectionTitleSnapshot &&
            requested.originalSectionRoleSnapshot ==
            persisted.originalSectionRoleSnapshot &&
            requested.originalSectionOrderSnapshot ==
            persisted.originalSectionOrderSnapshot &&
            requested.originalSectionContentHashSnapshot ==
            persisted.originalSectionContentHashSnapshot &&
            requested.subjectKey == persisted.subjectKey &&
            requested.sourceMessageId == persisted.sourceMessageId &&
            requested.sourceProposalId == persisted.sourceProposalId &&
            requested.createdAt == persisted.createdAt

    private fun isPersistedPair(
        previous: DecisionRecord,
        replacement: DecisionRecord,
        requestedReplacement: DecisionRecord,
    ): Boolean =
        previous.decisionState == DecisionState.SUPERSEDED &&
            replacement.supersedesId == previous.id &&
            sameReplacementPayload(requestedReplacement, replacement)

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

/**
 * Rollback interno da B2B: carrega o resultado tipado para fora da transação
 * sem deixar a substituta parcialmente inserida.
 */
private class SupersessionRollback(
    val result: SupersessionResult,
) : RuntimeException()

private sealed interface PairValidation {
    data class Valid(val pair: SupersededPair) : PairValidation
    data class Failed(val result: SupersessionResult) : PairValidation
}
