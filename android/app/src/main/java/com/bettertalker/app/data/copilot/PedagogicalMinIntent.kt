package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Referent
import com.bettertalker.app.data.util.normalizeText

/**
 * PEDAGOGY-1-MIN / P1 — reconhecimento de intenções pedagógicas mínimas.
 *
 * CONTRATO PURO, AINDA SEM INTEGRAÇÃO PRODUTIVA: reconhecer uma intenção
 * não entrega a funcionalidade ao usuário. Nenhuma resposta é postada,
 * nenhum aceite é persistido e nenhum conteúdo é inserido no editor.
 *
 * Reutiliza os resolvedores certificados (#47/#51/#52) para a identidade
 * do referente; não duplica regras de isolamento nota/seção nem de
 * ambiguidade. A relação texto bíblico → tema fica adiada até contrato
 * TRUST seguro.
 */
object PedagogicalMinIntent {

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
    )

    /** Saídas tipadas: reformular ≠ desenvolver ≠ aprofundar ≠ inserir. */
    sealed interface Outcome {
        data class Reformulate(val referent: Referent, val simplify: Boolean) : Outcome
        data class DevelopReferent(val referent: Referent) : Outcome

        /** Reconhecimento apenas — sem persistência, aceite ou inserção. */
        data object PositiveFeedback : Outcome

        /** Referente ambíguo: o resolvedor certificado pede esclarecimento. */
        data object Clarify : Outcome

        /** Outra intenção: permanece nos fluxos certificados existentes. */
        data object Delegate : Outcome
    }

    private val REFORMULATE = Regex(
        """\b(reformul\w*|simplific\w*|simplifiq\w*|parafrase\w*|reexplique|explique[\s]+de[\s]+outra[\s]+(maneira|forma)|explique[\s]+com[\s]+outras[\s]+palavras|explique[\s]+mais[\s]+simples|diga[\s]+de[\s]+outro[\s]+jeito|de[\s]+outra[\s]+(maneira|forma))\b"""
    )

    private val DEVELOP_REFERENT = Regex(
        """\b(desenvolv\w*|aprofund\w*)\b.*\b(essa|esta|est[eé]|isso|isto)\b|\bcomo[\s]+posso[\s]+desenvolver[\s]+essa[\s]+ideia\b"""
    )

    private val POSITIVE = Regex(
        """\b(gostei|achei[\s]+(bom|boa|ótimo|otimo)|boa[\s]+sugest[aã]o|ótimo|otimo|legal|excelente|concordo)\b"""
    )

    /** Pedidos de inserção e seções explícitas pertencem aos fluxos certificados. */
    private val EXPLICIT_FLOW = Regex(
        """\b(coloque|coloca|insira|adicione|ponto|seção|secao|t[oó]pico)\b.*\d{0,2}\b|\b(coloque|coloca|insira|adicione)\b"""
    )

    private fun resolveReferent(context: Context): Referent =
        ConversationalReferentResolver.resolve(
            "Explique melhor", // frase canônica: reusa o contrato certificado
            ConversationalReferentResolver.Context(
                noteId = context.noteId,
                chatScope = if (context.focus == Focus.WHOLE) null else context.sectionId,
                focus = context.focus,
                lastCard = context.lastCard,
                lastCardNoteId = context.lastCardNoteId,
                lastCardSectionId = context.lastCardSectionId,
                lastSuggestion = context.lastSuggestion,
                lastSuggestionNoteId = context.lastSuggestionNoteId,
                lastSuggestionSectionId = context.lastSuggestionSectionId,
                lastResponse = context.lastResponse,
            ),
        )


    fun resolve(request: String, context: Context): Outcome {
        val t = normalizeText(request)
        if (t.isBlank()) return Outcome.Delegate
        // Inserção e seção explícita pertencem aos fluxos certificados.
        if (EXPLICIT_FLOW.containsMatchIn(t)) return Outcome.Delegate
        val reformulates = REFORMULATE.containsMatchIn(t)
        val develops = DEVELOP_REFERENT.containsMatchIn(t)
        if (!reformulates && !develops) {
            // Feedback positivo é apenas reconhecido — sem aceite persistido.
            return if (POSITIVE.containsMatchIn(t)) Outcome.PositiveFeedback else Outcome.Delegate
        }
        return when (val referent = resolveReferent(context)) {
            is Referent.RequestClarification -> Outcome.Clarify
            Referent.ProceedNormal -> Outcome.Delegate
            else -> when {
                reformulates -> Outcome.Reformulate(referent, t.contains("simples"))
                develops -> Outcome.DevelopReferent(referent)
                else -> Outcome.Delegate
            }
        }
    }
}
