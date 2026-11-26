package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Referent
import com.bettertalker.app.data.copilot.PedagogicalMinIntent.Outcome

/**
 * PEDAGOGY-1-MIN / P5 — ponte PURA entre a intenção pedagógica certificada
 * (#62) e os contratos de continuidade certificados (#47/#52).
 *
 * NÃO é integração produtiva: não posta mensagens, não chama provedor,
 * não persiste aceite, não avança estado, não substitui SourceGate/TRUST
 * e não simplifica texto de fato — [Dispatch.Reformulate.simplify] é
 * apenas uma flag de intenção para o futuro handler.
 *
 * A identidade do referente vem dos resolvedores certificados; aqui há
 * somente verificação de origem com os metadados já presentes no contexto.
 */
object PedagogicalContinuityBridge {

    data class Context(
        val noteId: String?,
        val sectionId: String?,
        val focus: Focus = Focus.SECTION,
        val lastCard: CardPayload? = null,
        val lastCardNoteId: String? = null,
        val lastCardSectionId: String? = null,
        val lastSuggestion: NextStepSuggestion? = null,
        val lastSuggestionNoteId: String? = null,
        val lastSuggestionSectionId: String? = null,
        val lastResponse: String? = null,
        /** Precedência de consentimento — pertence ao gate certificado. */
        val pendingConsent: Boolean = false,
        /** Comando explícito — pertence ao router certificado. */
        val explicitCommand: Boolean = false,
    )

    enum class Source { CARD, SUGGESTION, SECTION }

    sealed interface Dispatch {
        data class Reformulate(
            val referent: Referent,
            val source: Source,
            val noteId: String?,
            val sectionId: String?,
            /** Flag de intenção — nenhum comportamento de simplificação foi implementado. */
            val simplify: Boolean,
        ) : Dispatch

        data class Develop(
            val referent: Referent,
            val source: Source,
            val noteId: String?,
            val sectionId: String?,
        ) : Dispatch

        /** Reconhecimento sem efeito: nada é aceito, avançado ou inserido. */
        data object PositiveFeedback : Dispatch

        data class Clarify(val reason: String) : Dispatch
        data object Delegate : Dispatch
    }

    private fun intentContext(c: Context) = PedagogicalMinIntent.Context(
        noteId = c.noteId,
        sectionId = c.sectionId,
        focus = c.focus,
        lastCard = c.lastCard,
        lastCardNoteId = c.lastCardNoteId,
        lastCardSectionId = c.lastCardSectionId,
        lastSuggestion = c.lastSuggestion,
        lastSuggestionNoteId = c.lastSuggestionNoteId,
        lastSuggestionSectionId = c.lastSuggestionSectionId,
        lastResponse = c.lastResponse,
    )

    private fun originConflict(c: Context): Boolean {
        val cardConflict = c.lastCard != null && (
            c.lastCardNoteId == null || c.lastCardNoteId != c.noteId ||
                (c.focus != Focus.WHOLE &&
                    (c.lastCardSectionId == null || c.lastCardSectionId != c.sectionId))
            )
        val suggestionConflict = c.lastSuggestion != null && (
            c.lastSuggestionNoteId == null || c.lastSuggestionNoteId != c.noteId ||
                (c.focus != Focus.WHOLE &&
                    (c.lastSuggestionSectionId == null || c.lastSuggestionSectionId != c.sectionId))
            )
        return cardConflict || suggestionConflict
    }

    private fun sourceOf(referent: Referent, c: Context): Pair<Source, String?>? = when (referent) {
        is Referent.DeepenCard ->
            if (referent.card.targetKind == null || referent.card.targetId.isNullOrBlank()) null
            else Source.CARD to referent.card.targetId
        is Referent.ExplainSuggestion -> Source.SUGGESTION to c.lastSuggestionSectionId
        is Referent.ExplainActiveSection -> Source.SECTION to referent.sectionId
        else -> null
    }

    fun dispatch(request: String, context: Context): Dispatch {
        // Precedência explícita: consentimento e comandos pertencem aos fluxos certificados.
        if (context.pendingConsent || context.explicitCommand) return Dispatch.Delegate
        return when (val outcome = PedagogicalMinIntent.resolve(request, intentContext(context))) {
            Outcome.PositiveFeedback -> Dispatch.PositiveFeedback
            Outcome.Clarify -> Dispatch.Clarify("referente ambíguo")
            Outcome.Delegate -> Dispatch.Delegate
            is Outcome.Reformulate, is Outcome.DevelopReferent -> {
                if (originConflict(context)) {
                    return Dispatch.Clarify("origem do referente não confere")
                }
                val referent = when (outcome) {
                    is Outcome.Reformulate -> outcome.referent
                    is Outcome.DevelopReferent -> outcome.referent
                    else -> return Dispatch.Delegate
                }
                val source = sourceOf(referent, context)
                    ?: return Dispatch.Clarify("referente sem identidade comprovada")
                when (outcome) {
                    is Outcome.Reformulate -> Dispatch.Reformulate(
                        referent, source.first, context.noteId, source.second, outcome.simplify,
                    )
                    is Outcome.DevelopReferent -> Dispatch.Develop(
                        referent, source.first, context.noteId, source.second,
                    )
                    else -> Dispatch.Delegate
                }
            }
        }
    }
}
