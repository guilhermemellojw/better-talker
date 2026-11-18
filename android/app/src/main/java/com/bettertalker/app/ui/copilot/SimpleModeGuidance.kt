package com.bettertalker.app.ui.copilot

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
