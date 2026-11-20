package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.data.copilot.hasCurrentApplication
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 1C.2.2A — grupos visuais da memória persistida (somente leitura). */
enum class DecisionMemoryTone { ACTIVE, ATTENTION, HISTORY }

/** Modelo de apresentação de uma decisão; nunca exibe ids/hashes/enums. */
data class DecisionMemoryItemUi(
    /** Apenas chave de lista; nunca renderizado. */
    val id: String,
    /** Ordenação cronológica; nunca renderizado diretamente. */
    val createdAt: Long,
    val text: String,
    val sectionLabel: String,
    val statusLabel: String,
    val tone: DecisionMemoryTone,
    val originLabel: String,
    val dateLabel: String,
    val applicationLabel: String,
    val replacesText: String? = null,
    val replacedByText: String? = null,
)

data class DecisionMemoryUiState(
    val active: List<DecisionMemoryItemUi> = emptyList(),
    val attention: List<DecisionMemoryItemUi> = emptyList(),
    val history: List<DecisionMemoryItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean
        get() = active.isEmpty() && attention.isEmpty() && history.isEmpty()
}

private enum class MemoryGroup { ACTIVE, ATTENTION, HISTORY }

private fun defaultDateLabel(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        .format(Date(epochMillis))

/**
 * 1C.2.2A — transforma o ledger em uma visão honesta e somente leitura.
 *
 * - ACTIVE/CONFIRMED com vínculo válido: "Ativa".
 * - ACTIVE/CONFIRMED órfã, pendente ou com aplicação STALE: "Precisa de atenção".
 * - SUPERSEDED/REMOVED/RESOLVED e naturezas históricas: "Histórico".
 *
 * Não infere APPLIED nem uso pelo Mentor; apenas reflete o persistido.
 */
fun buildDecisionMemoryUi(
    decisions: List<DecisionRecord>,
    dateFormatter: (Long) -> String = ::defaultDateLabel,
): DecisionMemoryUiState {
    val byId = decisions.associateBy { it.id }
    val replacementByPrevious = decisions
        .mapNotNull { decision -> decision.supersedesId?.let { it to decision } }
        .toMap()

    val grouped = decisions.map { decision ->
        val group = groupOf(decision)
        val previous = decision.supersedesId?.let { byId[it] }
        val replacement = replacementByPrevious[decision.id]
        group to DecisionMemoryItemUi(
            id = decision.id,
            createdAt = decision.createdAt,
            text = decision.text,
            sectionLabel = sectionLabelOf(decision),
            statusLabel = statusLabelOf(decision),
            tone = when (group) {
                MemoryGroup.ACTIVE -> DecisionMemoryTone.ACTIVE
                MemoryGroup.ATTENTION -> DecisionMemoryTone.ATTENTION
                MemoryGroup.HISTORY -> DecisionMemoryTone.HISTORY
            },
            originLabel = originLabelOf(decision),
            dateLabel = dateFormatter(decision.createdAt),
            applicationLabel = applicationLabelOf(decision),
            replacesText = previous?.text,
            replacedByText = replacement?.text,
        )
    }

    fun of(group: MemoryGroup): List<DecisionMemoryItemUi> =
        grouped.filter { it.first == group }
            .map { it.second }
            .sortedWith(compareByDescending<DecisionMemoryItemUi> { it.createdAt }
                .thenByDescending { it.id })

    return DecisionMemoryUiState(
        active = of(MemoryGroup.ACTIVE),
        attention = of(MemoryGroup.ATTENTION),
        history = of(MemoryGroup.HISTORY),
    )
}

private fun groupOf(decision: DecisionRecord): MemoryGroup = when {
    decision.kind == DecisionKind.CONFIRMED &&
        decision.decisionState == DecisionState.ACTIVE &&
        (decision.sectionLink == SectionLink.VALID ||
            decision.sectionLink == SectionLink.NOT_APPLICABLE) &&
        decision.applicationState != ApplicationState.STALE -> MemoryGroup.ACTIVE

    decision.kind == DecisionKind.CONFIRMED &&
        decision.decisionState == DecisionState.ACTIVE -> MemoryGroup.ATTENTION

    decision.kind == DecisionKind.OPEN &&
        decision.decisionState == DecisionState.ACTIVE -> MemoryGroup.ATTENTION

    else -> MemoryGroup.HISTORY
}

private fun statusLabelOf(decision: DecisionRecord): String = when {
    decision.kind == DecisionKind.REJECTED -> "Recusada"
    decision.kind == DecisionKind.OPEN -> "Em aberto"
    decision.kind == DecisionKind.PREFERENCE -> "Preferência"
    decision.decisionState == DecisionState.SUPERSEDED -> "Substituída"
    decision.decisionState == DecisionState.REMOVED -> "Removida"
    decision.decisionState == DecisionState.RESOLVED -> "Resolvida"
    decision.decisionState == DecisionState.ACTIVE &&
        decision.applicationState == ApplicationState.STALE ->
        "Aplicação desatualizada"
    decision.decisionState == DecisionState.ACTIVE &&
        decision.sectionLink == SectionLink.ORPHANED -> "Sem seção"
    decision.decisionState == DecisionState.ACTIVE &&
        decision.sectionLink == SectionLink.PENDING_RECONFIRMATION ->
        "Precisa reconfirmar"
    decision.decisionState == DecisionState.ACTIVE -> "Ativa"
    else -> "Registro"
}

private fun sectionLabelOf(decision: DecisionRecord): String = when {
    decision.scope == DecisionScope.NOTE -> "Nota inteira"
    decision.sectionLink == SectionLink.ORPHANED -> "Seção removida"
    !decision.sectionTitleSnapshot.isNullOrBlank() ->
        decision.sectionTitleSnapshot ?: "Seção"
    else -> "Seção"
}

private fun originLabelOf(decision: DecisionRecord): String = when {
    decision.source == DecisionSource.STRUCTURE_ACCEPT &&
        decision.sourceProposalId != null -> "Aceite de estrutura"
    decision.source == DecisionSource.STRUCTURE_ACCEPT -> "Registro anterior"
    decision.source == DecisionSource.EXPLICIT_USER -> "Decisão do usuário"
    decision.source == DecisionSource.DECISION_CONFIRMATION -> "Decisão confirmada"
    else -> "Origem indisponível"
}

private fun applicationLabelOf(decision: DecisionRecord): String = when {
    decision.kind != DecisionKind.CONFIRMED -> "Não se aplica"
    decision.applicationState == ApplicationState.STALE ->
        "O texto mudou depois da aplicação"
    decision.applicationState == ApplicationState.APPLIED &&
        decision.hasCurrentApplication() -> "Aplicada ao texto"
    decision.applicationState == ApplicationState.APPLIED ->
        "Aplicada ao texto no histórico"
    else -> "Texto do discurso não alterado"
}
