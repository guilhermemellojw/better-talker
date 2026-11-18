package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.ConfirmedApproachWrite
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.SectionSnapshot
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.repo.DecisionMemoryRepository
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.SectionPlan
import com.bettertalker.app.domain.speech.SpeechSection

/**
 * 1C.2.1B1 — evento explícito de aceite da proposta estrutural.
 *
 * Só existe quando o usuário acionou "aceitar" numa proposta válida. O
 * `proposalId` é o id estável da UI (`StructureProposalUi.id`), não o id
 * interno do OutlineProposal.
 *
 * A decisão é LOCAL: ainda não participa do backup v2, do sync nem dos
 * prompts do Mentor. Esta etapa só grava o fato de que o usuário aceitou uma
 * proposta estrutural; não interpreta mensagens nem infere preferências.
 * Contradições/tópicos ficam para uma política explícita de B2.
 */
internal data class StructureAcceptRequest(
    val proposalId: String,
    val noteId: String,
    val sectionId: String,
    val proposal: OutlineProposal,
    val plan: SectionPlan,
    val summary: String,
    val sectionBaseHash: String,
    val allowEmptyRefs: Boolean,
)

internal sealed interface StructureAcceptResult {
    data class Applied(
        val approachWrite: ConfirmedApproachWrite,
        val decision: DecisionRecord,
    ) : StructureAcceptResult

    /** A decisão determinística já existia; nada foi reescrito. */
    data class AlreadyApplied(val decision: DecisionRecord) : StructureAcceptResult

    data object Stale : StructureAcceptResult
    data object Invalid : StructureAcceptResult
    data object MissingSection : StructureAcceptResult
}

/**
 * Executa o aceite estrutural numa única transação:
 *
 * 1. verifica se o evento determinístico já foi persistido;
 * 2. se novo, revalida a proposta;
 * 3. relê a seção persistida e barra stale por hash;
 * 4. aplica a estrutura;
 * 5. grava a decisão explícita (`STRUCTURE_ACCEPT`).
 *
 * Um evento já persistido retorna `AlreadyApplied` antes do validador e do
 * gate de stale: repetir depois de uma edição posterior não altera seção,
 * decisão nem timestamps. Colisões de identidade (outra nota/seção/origem)
 * falham explicitamente. Se qualquer etapa nova falhar, a transação desfaz
 * seção e decisão juntas.
 */
internal class StructureAcceptService(
    private val transactionRunner: TransactionRunner,
    private val sectionDao: SpeechSectionDao,
    private val decisionMemory: DecisionMemoryRepository,
) {

    suspend fun accept(
        request: StructureAcceptRequest,
        now: Long = System.currentTimeMillis(),
    ): StructureAcceptResult = transactionRunner.run {
        val decisionId = structureAcceptDecisionId(request.proposalId)
        val existing = decisionMemory.get(decisionId)
        if (existing != null) {
            requireSameStructureAcceptIdentity(existing, request)
            return@run StructureAcceptResult.AlreadyApplied(existing)
        }

        if (!validateStructureAccept(request.proposal, request.allowEmptyRefs)) {
            return@run StructureAcceptResult.Invalid
        }
        val current = sectionDao.get(request.sectionId)?.toDomain()
            ?: return@run StructureAcceptResult.MissingSection
        require(current.noteId == request.noteId) {
            "seção ${request.sectionId} não pertence à nota ${request.noteId}"
        }

        when (
            val decision = decideStructureAccept(
                current = current,
                sectionBaseHash = request.sectionBaseHash,
                plan = request.plan,
                summary = request.summary,
                now = now,
            )
        ) {
            StructureAcceptDecision.Stale -> StructureAcceptResult.Stale

            is StructureAcceptDecision.Apply -> {
                sectionDao.upsert(decision.updated.toEntity())
                val record = buildStructureAcceptDecision(
                    proposalId = request.proposalId,
                    current = current,
                    updated = decision.updated,
                    summary = request.summary,
                    plan = request.plan,
                    now = now,
                )
                decisionMemory.save(record)
                StructureAcceptResult.Applied(decision.approachWrite, record)
            }
        }
    }

    /**
     * O id determinístico é global. Se já existe um registro com esse id, ele
     * precisa representar exatamente o mesmo evento: `STRUCTURE_ACCEPT`, mesmo
     * `sourceProposalId`, mesma seção e, quando a seção ainda existe, mesma
     * nota. Caso contrário a colisão é rejeitada em vez de virar idempotência
     * silenciosa.
     */
    private suspend fun requireSameStructureAcceptIdentity(
        existing: DecisionRecord,
        request: StructureAcceptRequest,
    ) {
        require(existing.source == DecisionSource.STRUCTURE_ACCEPT) {
            "colisão de id ${existing.id}: origem diferente de STRUCTURE_ACCEPT"
        }
        require(existing.scope == DecisionScope.SECTION) {
            "colisão de id ${existing.id}: escopo diferente de SECTION"
        }
        require(existing.sourceProposalId == request.proposalId) {
            "colisão de id ${existing.id}: sourceProposalId divergente"
        }
        require(existing.noteId == request.noteId) {
            "colisão de id ${existing.id}: nota divergente"
        }
        require(existing.sectionId == request.sectionId) {
            "colisão de id ${existing.id}: seção divergente"
        }
        val current = sectionDao.get(request.sectionId)
        if (current != null) {
            require(current.noteId == request.noteId) {
                "colisão de id ${existing.id}: seção pertence a outra nota"
            }
        }
    }
}

/**
 * Identidade determinística do aceite: o mesmo `proposalId` nunca cria duas
 * decisões. Não usa o id interno do OutlineProposal.
 */
internal fun structureAcceptDecisionId(proposalId: String): String {
    require(proposalId.isNotBlank()) { "proposalId vazio" }
    return "dec-structure-$proposalId"
}

/**
 * Texto exato da proposta aceita. Usa o resumo exibido ao usuário; se ele
 * vier vazio, usa a ideia central do plano. Nunca inventa preferências.
 */
internal fun structureAcceptDecisionText(summary: String, plan: SectionPlan): String {
    val text = summary.trim().ifEmpty { plan.mainIdea.trim() }
    require(text.isNotEmpty()) { "proposta sem texto para a decisão" }
    return text
}

/**
 * Snapshot da seção para a decisão. O hash reutiliza a MESMA função do
 * gate de stale do aceite (`sectionProposalBlock` + `hashText`).
 */
internal fun speechSectionSnapshot(section: SpeechSection): SectionSnapshot =
    SectionSnapshot(
        id = section.id,
        noteId = section.noteId,
        role = section.role,
        title = section.title.ifBlank { "Seção" },
        order = section.order,
        contentHash = hashText(sectionProposalBlock(section.title, section.contentHtml)),
    )

/**
 * Transformação pura evento → decisão. Não lê nem grava banco.
 *
 * `original*` representa a seção antes do aceite; `section*` representa a
 * última associação aplicada. `applicationState` fica NOT_APPLIED: aceite
 * estrutural não é aplicação de texto.
 */
internal fun buildStructureAcceptDecision(
    proposalId: String,
    current: SpeechSection,
    updated: SpeechSection,
    summary: String,
    plan: SectionPlan,
    now: Long,
): DecisionRecord {
    require(current.id == updated.id) { "aceite não pode trocar a seção" }
    require(current.noteId == updated.noteId) { "aceite não pode trocar a nota" }
    val original = speechSectionSnapshot(current)
    val applied = speechSectionSnapshot(updated)
    return DecisionRecord(
        id = structureAcceptDecisionId(proposalId),
        noteId = current.noteId,
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = DecisionState.ACTIVE,
        sectionLink = SectionLink.VALID,
        applicationState = ApplicationState.NOT_APPLIED,
        text = structureAcceptDecisionText(summary, plan),
        source = DecisionSource.STRUCTURE_ACCEPT,
        sectionId = updated.id,
        originalSectionId = current.id,
        candidateSectionId = null,
        sectionTitleSnapshot = applied.title,
        sectionRoleSnapshot = applied.role,
        sectionOrderSnapshot = applied.order,
        sectionContentHashSnapshot = applied.contentHash,
        originalSectionTitleSnapshot = original.title,
        originalSectionRoleSnapshot = original.role,
        originalSectionOrderSnapshot = original.order,
        originalSectionContentHashSnapshot = original.contentHash,
        sourceProposalId = proposalId,
        createdAt = now,
        updatedAt = now,
    )
}

/** Mapeia o resultado persistido para o estado visual do cartão. */
internal fun StructureAcceptResult.toStructureProposalUi(
    ui: CopilotViewModel.StructureProposalUi,
): CopilotViewModel.StructureProposalUi = when (this) {
    is StructureAcceptResult.Applied -> ui.copy(
        notice = structureAppliedNotice(approachWrite),
        applied = true,
    )

    is StructureAcceptResult.AlreadyApplied -> ui.copy(
        notice = STRUCTURE_APPLIED_NOTICE,
        applied = true,
    )

    StructureAcceptResult.Stale,
    StructureAcceptResult.MissingSection -> ui.copy(notice = STRUCTURE_STALE_NOTICE)

    StructureAcceptResult.Invalid -> ui.copy(notice = STRUCTURE_INVALID_NOTICE)
}

/** Falha de persistência: nunca marca `applied` nem anuncia memorização. */
internal fun structureAcceptFailure(
    ui: CopilotViewModel.StructureProposalUi,
): CopilotViewModel.StructureProposalUi = ui.copy(notice = STRUCTURE_APPLY_FAILED_NOTICE)

/** Rejeição é somente UI: não constrói request nem grava decisão. */
internal fun rejectedStructureProposal(
    ui: CopilotViewModel.StructureProposalUi,
): CopilotViewModel.StructureProposalUi = ui.copy(notice = STRUCTURE_REJECTED_NOTICE)
