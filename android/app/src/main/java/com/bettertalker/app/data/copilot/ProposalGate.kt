package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.normalizeText

/**
 * Ação pendente de proposta: o "Sim" no chat não é intenção genérica — só
 * executa quando a última fala do Copilot é o summary da proposta atual.
 *
 * Puro/testável: a decisão não conhece Compose nem Room.
 */

enum class ProposalGateAction { NONE, ACCEPT, REJECT, OTHER_SCOPE }

private val SHORT_YES = setOf(
    "sim", "pode", "manda", "claro", "isso", "aceito", "aceitar",
    "aplicar", "aplico", "confirmo", "confirmar", "ok", "beleza",
    "fechado", "vai", "faz",
)

private val SHORT_NO = setOf(
    "nao", "n", "deixa", "cancela", "cancelar", "rejeito", "rejeitar",
    "dispensa", "dispensar", "agora nao", "melhor nao",
)

/** Texto normalizado, sem pontuação de borda. */
internal fun proposalAnswerText(raw: String): String =
    normalizeText(raw).trim().trim('.', '!', ',', ';', ':', '?').trim()

/** Só respostas curtas contam; "sim, mas..." vai ao LLM. */
fun isShortProposalAnswer(raw: String): Boolean {
    val t = proposalAnswerText(raw)
    return t in SHORT_YES || t in SHORT_NO
}

fun proposalAnswerIsYes(raw: String): Boolean = proposalAnswerText(raw) in SHORT_YES

fun proposalAnswerIsNo(raw: String): Boolean = proposalAnswerText(raw) in SHORT_NO

/**
 * Summary legível que entra no histórico e no prompt. O card continua sendo
 * a UI da proposta; este texto é o que o modelo vê.
 */
fun proposalSummaryText(sectionTitle: String?, explanation: String?): String {
    val target = sectionTitle?.trim()?.takeIf { it.isNotBlank() }
        ?.let { "para a seção “$it”" }
        ?: "para a nota"
    val action = explanation?.trim()
        ?.trimEnd('.', '!', '?')
        ?.takeIf { it.isNotBlank() }
        ?: "aplicar o texto proposto"
    return "Proposta $target: $action. Quer que eu aplique?"
}

/**
 * Decide se a mensagem atual executa a proposta pendente.
 *
 * Só aceita quando:
 * - há proposta pendente e ainda não aplicada;
 * - a última mensagem do Copilot é o summary dessa proposta;
 * - a mensagem é uma resposta curta de sim/não.
 *
 * "Sim" de outra seção vira [ProposalGateAction.OTHER_SCOPE], nunca aplica
 * no lugar errado. Proposta aplicada não aceita de novo.
 */
fun proposalGateAction(
    text: String,
    hasPendingProposal: Boolean,
    applied: Boolean,
    proposalMessageId: String?,
    lastCopilotMessageId: String?,
    proposalSectionId: String?,
    currentScopeId: String?,
): ProposalGateAction {
    if (!hasPendingProposal || applied) return ProposalGateAction.NONE
    if (proposalMessageId.isNullOrBlank()) return ProposalGateAction.NONE
    if (lastCopilotMessageId != proposalMessageId) return ProposalGateAction.NONE
    if (!isShortProposalAnswer(text)) return ProposalGateAction.NONE
    val yes = proposalAnswerIsYes(text)
    if (yes && proposalSectionId != null && proposalSectionId != currentScopeId) {
        return ProposalGateAction.OTHER_SCOPE
    }
    return if (yes) ProposalGateAction.ACCEPT else ProposalGateAction.REJECT
}
