package com.bettertalker.app.data.db

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.domain.speech.SectionRole

/**
 * 1C.2.1A2 — mapeamento Room <-> domínio de decisões.
 *
 * Copia TODOS os campos, inclusive timestamps e os dois snapshots
 * (atual/última associação e original). Nenhum valor é recalculado aqui:
 * as regras vivem em DecisionTransitions.
 *
 * Enums são lidos de forma estrita. Uma linha corrompida deve falhar em vez
 * de virar silenciosamente outro estado.
 */
fun DecisionEntity.toDomain(): DecisionRecord = DecisionRecord(
    id = id,
    noteId = noteId,
    scope = DecisionScope.valueOf(scope),
    kind = DecisionKind.valueOf(kind),
    decisionState = DecisionState.valueOf(decisionState),
    sectionLink = SectionLink.valueOf(sectionLink),
    applicationState = ApplicationState.valueOf(applicationState),
    text = text,
    source = DecisionSource.valueOf(source),
    sectionId = sectionId,
    originalSectionId = originalSectionId,
    candidateSectionId = candidateSectionId,
    sectionTitleSnapshot = sectionTitleSnapshot,
    sectionRoleSnapshot = sectionRoleSnapshot?.let { SectionRole.valueOf(it) },
    sectionOrderSnapshot = sectionOrderSnapshot,
    sectionContentHashSnapshot = sectionContentHashSnapshot,
    originalSectionTitleSnapshot = originalSectionTitleSnapshot,
    originalSectionRoleSnapshot = originalSectionRoleSnapshot?.let { SectionRole.valueOf(it) },
    originalSectionOrderSnapshot = originalSectionOrderSnapshot,
    originalSectionContentHashSnapshot = originalSectionContentHashSnapshot,
    subjectKey = subjectKey,
    sourceMessageId = sourceMessageId,
    sourceProposalId = sourceProposalId,
    supersedesId = supersedesId,
    appliedAt = appliedAt,
    appliedHash = appliedHash,
    createdAt = createdAt,
    updatedAt = updatedAt,
    removedAt = removedAt,
)

fun DecisionRecord.toEntity(): DecisionEntity = DecisionEntity(
    id = id,
    noteId = noteId,
    scope = scope.name,
    kind = kind.name,
    decisionState = decisionState.name,
    sectionLink = sectionLink.name,
    applicationState = applicationState.name,
    text = text,
    source = source.name,
    sectionId = sectionId,
    originalSectionId = originalSectionId,
    candidateSectionId = candidateSectionId,
    sectionTitleSnapshot = sectionTitleSnapshot,
    sectionRoleSnapshot = sectionRoleSnapshot?.name,
    sectionOrderSnapshot = sectionOrderSnapshot,
    sectionContentHashSnapshot = sectionContentHashSnapshot,
    originalSectionTitleSnapshot = originalSectionTitleSnapshot,
    originalSectionRoleSnapshot = originalSectionRoleSnapshot?.name,
    originalSectionOrderSnapshot = originalSectionOrderSnapshot,
    originalSectionContentHashSnapshot = originalSectionContentHashSnapshot,
    subjectKey = subjectKey,
    sourceMessageId = sourceMessageId,
    sourceProposalId = sourceProposalId,
    supersedesId = supersedesId,
    appliedAt = appliedAt,
    appliedHash = appliedHash,
    createdAt = createdAt,
    updatedAt = updatedAt,
    removedAt = removedAt,
)
