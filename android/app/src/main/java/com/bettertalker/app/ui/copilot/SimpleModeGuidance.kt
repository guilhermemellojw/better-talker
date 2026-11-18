package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.QuickAction

/**
 * UX-A11Y-2A — orientação única do Modo simples.
 *
 * A prioridade é determinística e deriva apenas do estado real da tela.
 * Nenhuma ação é criada para um estado em que ela não pode ser executada.
 */
internal enum class SimpleGuidanceKind {
    SOURCES_BLOCKED,
    ERROR,
    PROPOSAL,
    GENERATING,
    NEXT_STEP,
    EMPTY,
    NONE,
}

internal data class SimpleModeGuidance(
    val kind: SimpleGuidanceKind,
    val title: String,
    val detail: String,
)

/**
 * Seleciona a única orientação principal do Modo simples.
 *
 * Ordem obrigatória:
 * 1. bloqueio de fontes/preparação;
 * 2. erro;
 * 3. proposta aguardando confirmação;
 * 4. geração em andamento;
 * 5. sugestão de próximo passo;
 * 6. conversa vazia;
 * 7. nenhuma orientação pendente.
 */
internal fun simpleModeGuidance(
    simpleMode: Boolean,
    sourcesBlocked: Boolean,
    hasError: Boolean,
    hasProposal: Boolean,
    isGenerating: Boolean,
    hasNextStep: Boolean,
    isEmptyConversation: Boolean,
): SimpleModeGuidance {
    if (!simpleMode) {
        return SimpleModeGuidance(SimpleGuidanceKind.NONE, "", "")
    }
    return when {
        sourcesBlocked -> SimpleModeGuidance(
            SimpleGuidanceKind.SOURCES_BLOCKED,
            "Prepare as fontes",
            "Resolva a pendência de fontes para o Mentor responder com segurança.",
        )
        hasError -> SimpleModeGuidance(
            SimpleGuidanceKind.ERROR,
            "Algo não deu certo",
            "Tente novamente quando estiver pronto.",
        )
        hasProposal -> SimpleModeGuidance(
            SimpleGuidanceKind.PROPOSAL,
            "Revise o texto proposto",
            "Nada é aplicado ao discurso sem a sua confirmação.",
        )
        isGenerating -> SimpleModeGuidance(
            SimpleGuidanceKind.GENERATING,
            "O Mentor está pensando",
            "Aguarde a resposta ou toque em Parar.",
        )
        hasNextStep -> SimpleModeGuidance(
            SimpleGuidanceKind.NEXT_STEP,
            "Próximo passo",
            "O Mentor preparou uma sugestão para continuar.",
        )
        isEmptyConversation -> SimpleModeGuidance(
            SimpleGuidanceKind.EMPTY,
            "Por onde começar?",
            "Escolha um caminho abaixo ou escreva sua pergunta.",
        )
        else -> SimpleModeGuidance(
            SimpleGuidanceKind.NONE,
            "",
            "",
        )
    }
}

/**
 * Atalhos locais priorizados no Modo simples. A ordem muda apenas a
 * apresentação; o texto de cada comando permanece idêntico ao modo padrão.
 */
internal fun simpleModeLocalShortcuts(localActions: List<String>): List<String> {
    val priority = listOf(
        "Próximo passo",
        "Como desenvolver este ponto",
        "Diagnóstico do esboço",
        "Resumir",
        "Mostrar fontes",
    )
    return priority.filter { it in localActions } +
        localActions.filter { it !in priority }
}

/** Limite de atalhos prioritários no Modo simples antes de "Ver outras opções". */
internal const val SIMPLE_MODE_SHORTCUT_LIMIT = 3

/** UX-A11Y-2A — proposta de texto ainda aguarda decisão do usuário. */
internal fun CopilotViewModel.ProposalUi.isPendingProposal(): Boolean =
    !applied && notice == null

/** UX-A11Y-2A — proposta de estrutura de seção ainda aguarda decisão. */
internal fun CopilotViewModel.StructureProposalUi.isPendingProposal(): Boolean =
    !applied && notice == null

/** UX-A11Y-2A — proposta de estrutura da nota ainda aguarda decisão. */
internal fun CopilotViewModel.NoteStructureProposalUi.isPendingProposal(): Boolean =
    !applied && notice == null

/** UX-A11Y-2A — opções de ângulo ainda não foram escolhidas/resolvidas. */
internal fun CopilotViewModel.MultiProposalUi.isPendingProposal(): Boolean =
    chosen == null && notice == null

/**
 * UX-A11Y-2A — há alguma proposta real aguardando confirmação?
 *
 * Propostas aplicadas, rejeitadas, com aviso terminal ou com opção já
 * escolhida não bloqueiam a orientação de próximo passo.
 */
internal fun hasPendingProposal(
    proposal: CopilotViewModel.ProposalUi?,
    structureProposal: CopilotViewModel.StructureProposalUi?,
    noteStructureProposal: CopilotViewModel.NoteStructureProposalUi?,
    multiProposal: CopilotViewModel.MultiProposalUi?,
): Boolean = proposal?.isPendingProposal() == true ||
    structureProposal?.isPendingProposal() == true ||
    noteStructureProposal?.isPendingProposal() == true ||
    multiProposal?.isPendingProposal() == true

/** Plano dos atalhos realmente exibidos no Modo simples. */
internal data class SimpleModeShortcutPlan(
    val quickActions: List<QuickAction>,
    val localActions: List<String>,
    val showAttach: Boolean,
    val showLocalNeeds: Boolean,
    val hasMore: Boolean,
) {
    val labels: List<String>
        get() = buildList {
            addAll(quickActions.map { it.label })
            addAll(localActions)
            if (showAttach) add("Anexar esboço")
            if (showLocalNeeds) add("Necessidades Locais")
        }
}

/**
 * UX-A11Y-2A — seleciona até [limit] atalhos, priorizando uma ação de
 * melhoria, um próximo passo local e, por último, Necessidades Locais.
 */
internal fun simpleModeShortcutPlan(
    quickActions: List<QuickAction>,
    localActions: List<String>,
    limit: Int = SIMPLE_MODE_SHORTCUT_LIMIT,
): SimpleModeShortcutPlan {
    val budget = limit.coerceAtLeast(0)
    val prioritizedLocal = simpleModeLocalShortcuts(localActions)
    val chosenQuick = mutableListOf<QuickAction>()
    val chosenLocal = mutableListOf<String>()
    val attachAvailable = localActions.isEmpty()
    var showAttach = false
    var showNeeds = false

    fun used() = chosenQuick.size + chosenLocal.size +
        (if (showAttach) 1 else 0) + (if (showNeeds) 1 else 0)

    if (used() < budget && quickActions.isNotEmpty()) {
        chosenQuick.add(quickActions.first())
    }
    if (used() < budget && prioritizedLocal.isNotEmpty()) {
        chosenLocal.add(prioritizedLocal.first())
    }
    if (used() < budget && attachAvailable) {
        showAttach = true
    }
    if (used() < budget) {
        showNeeds = true
    }
    val remainingQuick = (budget - used()).coerceAtLeast(0)
    if (remainingQuick > 0) {
        chosenQuick.addAll(quickActions.drop(1).take(remainingQuick))
    }
    val remainingLocal = (budget - used()).coerceAtLeast(0)
    if (remainingLocal > 0) {
        chosenLocal.addAll(prioritizedLocal.drop(1).take(remainingLocal))
    }
    val total = quickActions.size + localActions.size + 1 +
        (if (attachAvailable) 1 else 0)
    return SimpleModeShortcutPlan(
        quickActions = chosenQuick,
        localActions = chosenLocal,
        showAttach = showAttach,
        showLocalNeeds = showNeeds,
        hasMore = total > used(),
    )
}
