package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.normalizeText

/**
 * PEDAGOGY-0A — Resolvedor determinístico de referente conversacional.
 *
 * Pedidos de aprofundamento curtos ("Explique melhor", "Não entendi",
 * "Dá outro exemplo") não carregam o referente explícito. O resolvedor
 * resolve quem o pedido refere, usando o contexto conversacional.
 *
 * Saídas tipadas (P2): Aprofundar o card / Explicar a última sugestão /
 * Explicar o tópico ativo / Solicitar esclarecimento / Prosseguir pelo
 * fluxo conversacional normal.
 *
 * Puro/testável: sem UI, Room, rede ou LLM. Não presume que toda mensagem
 * curta é solicitação simples.
 */
object ConversationalReferentResolver {

    /** Estado do foco conversacional (P2 — entrada do resolvedor). */
    enum class Focus { SECTION, WHOLE }

    /**
     * Estado do conversado para a resolução do referente (P2).
     *
     * [lastCardNoteId] / [lastCardSectionId] e [lastSuggestionNoteId] /
     * [lastSuggestionSectionId] são metadados de ORIGEM transportados no
     * próprio Context (F3 — P2-FIX): não alteram o formato persistido de
     * `CardPayload` nem de `NextStepSuggestion`. Sem eles a origem do
     * referente não pode ser confirmada e o resolvedor pede esclarecimento.
     */
    data class Context(
        val noteId: String?,
        val chatScope: String?,
        val focus: Focus? = null,
        val lastCard: CardPayload? = null,
        val lastCardNoteId: String? = null,
        val lastCardSectionId: String? = null,
        val lastSuggestion: NextStepSuggestion? = null,
        val lastSuggestionNoteId: String? = null,
        val lastSuggestionSectionId: String? = null,
        val lastResponse: String? = null,
    )

    /** Saídas tipadas (P2). */
    sealed interface Referent {
        data class DeepenCard(val card: CardPayload) : Referent
        data class ExplainSuggestion(val suggestion: NextStepSuggestion) : Referent
        data class ExplainActiveSection(val sectionId: String) : Referent
        data class RequestClarification(val reason: String) : Referent
        data object ProceedNormal : Referent
    }

    // F2 — expressão de uma única linha: a construção multilinha anterior
    // introduzia indentação literal depois do trimIndent/replace.
    private val DEEPEN = Regex(
        """\b((explique|explicar)[\s]+melhor|nao[\s]+(entendi|compreendi)|d[ae][\s]+outro[\s]+exemplo|outros[\s]+exemplos|exemplo[\s]+diferente|vamos[\s]+continuar([\s]+de[\s]+onde([\s]+paramos)?)?|continuar[\s]+de[\s]+onde([\s]+paramos)?|fale[\s]+mais)\b"""
    )

    /** F6 — conjugações de desenvolver/escrever/redigir (e rascunho). */
    private val DRAFT = Regex(
        """\b(desenvolv\w*|escrev\w*|redig\w*|rascunho\w*)\b"""
    )

    /**
     * Remove a expressão de aprofundamento do pedido normalizado. O que
     * sobra (se algo sobrar) é uma referência explícita ao referente —
     * precisa casar por identidade inequívoca (F5) ou pede esclarecimento.
     */
    private fun residual(t: String): String =
        t.replace(DEEPEN, " ").replace(Regex("""\s+"""), " ").trim()

    /** F5 — comparação por limites de palavra, nunca substring. */
    private fun mentions(t: String, value: String?): Boolean {
        val v = value?.takeIf { it.isNotBlank() }?.let(::normalizeText) ?: return false
        return Regex("""\b${Regex.escape(v)}\b""").containsMatchIn(t)
    }

    /** F3 — origem do card confirmada: nota, seção e validade do card. */
    private fun cardOriginConfirmed(c: Context): Boolean {
        val card = c.lastCard ?: return false
        if (card.targetKind == null || card.targetId.isNullOrBlank()) return false
        if (!canInsertCard(card.status)) return false
        if (c.lastCardNoteId == null || c.lastCardNoteId != c.noteId) return false
        // F3 — foco WHOLE: não há seção ativa para confirmar um card de seção.
        if (c.focus == Focus.WHOLE) return card.targetKind == CardTargetKind.NOTE
        return c.lastCardSectionId != null && c.lastCardSectionId == c.chatScope
    }

    /** F3 — origem da sugestão confirmada: nota e seção ativas. */
    private fun suggestionOriginConfirmed(c: Context): Boolean {
        if (c.lastSuggestion == null) return false
        if (c.lastSuggestionNoteId == null || c.lastSuggestionNoteId != c.noteId) return false
        if (c.focus == Focus.WHOLE) return false
        return c.lastSuggestionSectionId != null && c.lastSuggestionSectionId == c.chatScope
    }

    /** Referência explícita casada por identidade inequívoca (F5). */
    private fun explicitReferent(t: String, c: Context): Referent? {
        if (cardOriginConfirmed(c)) {
            val card = c.lastCard!!
            if (mentions(t, card.targetLabel) || mentions(t, card.targetId)) {
                return Referent.DeepenCard(card)
            }
        }
        if (suggestionOriginConfirmed(c)) {
            val s = c.lastSuggestion!!
            if (mentions(t, s.label)) return Referent.ExplainSuggestion(s)
        }
        return null
    }

    /**
     * Resolve o referente do pedido atual. [request] = texto do pedido do
     * usuário; [context] = estado conversacional.
     */
    fun resolve(request: String, context: Context): Referent {
        val t = normalizeText(request)
        if (t.isBlank()) return Referent.RequestClarification("mensagem vazia")
        // F6 — intenção explícita de redação vence (inclusive em pedido misto).
        if (DRAFT.containsMatchIn(t)) return Referent.ProceedNormal
        if (!DEEPEN.containsMatchIn(t)) return Referent.ProceedNormal

        val left = residual(t)
        if (left.isNotBlank()) {
            // F5 — referência explícita não confirmada não cai no card
            // anterior: pede esclarecimento.
            explicitReferent(t, context)?.let { return it }
            return Referent.RequestClarification("referência explícita sem origem confirmada")
        }

        if (context.lastCard != null) {
            if (cardOriginConfirmed(context)) {
                // Troca de seção: o card antigo não é o referente; o tópico
                // ativo é (quando a seção ativa existe e é inequívoca).
                if (context.chatScope != null && context.lastCard.targetId != null &&
                    context.chatScope != context.lastCard.targetId
                ) {
                    return Referent.ExplainActiveSection(context.chatScope)
                }
                return Referent.DeepenCard(context.lastCard)
            }
        }
        if (context.lastSuggestion != null && suggestionOriginConfirmed(context)) {
            return Referent.ExplainSuggestion(context.lastSuggestion)
        }
        // F4 — troca de nota com mesmo ID de seção: o ID igual não prova
        // identidade de escopo entre notas diferentes.
        if (context.lastCard != null && context.lastCardNoteId != null &&
            context.lastCardNoteId != context.noteId && context.chatScope != null &&
            context.chatScope == context.lastCard.targetId
        ) {
            return Referent.RequestClarification("troca de nota com mesmo id de seção")
        }
        if (context.chatScope != null && context.focus != Focus.WHOLE) {
            return Referent.ExplainActiveSection(context.chatScope)
        }
        return Referent.RequestClarification("não há referente com origem confirmada")
    }
}
