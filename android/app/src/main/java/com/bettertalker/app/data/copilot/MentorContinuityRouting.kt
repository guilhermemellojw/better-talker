package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Capabilities
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Message
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Outcome
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.TargetState
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Referent

/**
 * MENTOR-INTEGRATION-P9 — ponte pura entre os contratos já certificados
 * (#47 resolvedor, #51 recuperação) e o fluxo do Mentor.
 *
 * Não reimplementa nenhuma regra dos dois componentes: apenas coleta as
 * saídas tipadas e traduz em ações que o ViewModel executa. Puro/testável:
 * sem ViewModel, Room, rede, LLM ou editor.
 */
object MentorContinuityRouting {

    /** Ações executáveis pelo chamador — nenhuma autoriza inserção no editor. */
    sealed interface Action {
        data class DeepenCard(val card: CardPayload, val topic: String) : Action
        data class ExplainSuggestion(val label: String, val detail: String) : Action
        data class ExplainActiveSection(val sectionId: String, val title: String) : Action
        data class Clarify(val reason: String) : Action

        /** Fluxo conversacional atual integralmente preservado. */
        data object ProceedNormal : Action
    }

    /** Capacidades informativas propagadas para o chamador (sem autorização). */
    data class Plan(
        val action: Action,
        val capabilities: Capabilities? = null,
    )

    /**
     * Compõe recuperação → resolvedor → tradução de saída.
     *
     * [messages] são mensagens persistidas projetadas; [targets] vem do
     * estado real do alvo; a sugestão e sua origem são voláteis — se a
     * origem não puder ser comprovada, o resolvedor pede esclarecimento.
     */
    fun plan(
        request: String,
        noteId: String?,
        scope: String?,
        focus: Focus,
        messages: List<Message>,
        targets: Map<String, TargetState>,
        suggestion: NextStepSuggestion?,
        suggestionNoteId: String?,
        suggestionSectionId: String?,
        lastResponse: String?,
        sectionTitles: Map<String, String>,
    ): Plan {
        val resolvedScope = if (focus == Focus.WHOLE) null else scope
        val recovered = ConversationalReferentRecovery.recover(
            messages = messages,
            context = ConversationalReferentRecovery.Context(
                noteId = noteId.orEmpty(),
                sectionId = resolvedScope,
                targets = targets,
            ),
        )
        // Ambiguidade da recuperação é sinal de falha segura: nunca se
        // escolhe um referente e nunca se cai em card antigo por omissão.
        if (recovered is Outcome.Ambiguous) {
            return Plan(Action.Clarify("referente ambíguo"))
        }
        val found = recovered as? Outcome.Found
        // O resolvedor usa `canInsertCard` como guarda de origem; um card
        // APPLIED é referente conversacional legítimo (requisito P9) embora
        // não seja reinserível. Normaliza-se APENAS o status para validação
        // de identidade — o card devolvido na ação permanece o original.
        val validationCard = if (found?.card?.status == CardStatus.APPLIED) {
            found.card.copy(status = CardStatus.AVAILABLE)
        } else {
            found?.card
        }
        val referent = ConversationalReferentResolver.resolve(
            request,
            ConversationalReferentResolver.Context(
                noteId = noteId,
                chatScope = resolvedScope,
                focus = focus,
                lastCard = validationCard,
                lastCardNoteId = found?.let { noteId },
                lastCardSectionId = found?.sectionId,
                lastSuggestion = suggestion,
                lastSuggestionNoteId = suggestion?.let { suggestionNoteId },
                lastSuggestionSectionId = suggestion?.let { suggestionSectionId },
                lastResponse = lastResponse,
            ),
        )
        val action = when (referent) {
            is Referent.DeepenCard -> {
                // A ação devolve SEMPRE o card original: a normalização de
                // status serve apenas à validação de origem no resolvedor.
                // Nunca altera payload persistido, estado visível nem autoriza
                // reinserção (capacidade vem da recuperação, não daqui).
                val original = found?.card ?: referent.card
                Action.DeepenCard(
                    card = original,
                    topic = original.targetLabel?.takeIf { it.isNotBlank() }
                        ?: original.targetId?.takeIf { it.isNotBlank() }
                        ?: original.summary,
                )
            }
            is Referent.ExplainSuggestion -> Action.ExplainSuggestion(
                label = referent.suggestion.label,
                detail = referent.suggestion.detail,
            )
            is Referent.ExplainActiveSection -> Action.ExplainActiveSection(
                sectionId = referent.sectionId,
                title = sectionTitles[referent.sectionId] ?: referent.sectionId,
            )
            is Referent.RequestClarification -> Action.Clarify(referent.reason)
            Referent.ProceedNormal -> Action.ProceedNormal
        }
        return Plan(action = action, capabilities = found?.capabilities)
    }
}
