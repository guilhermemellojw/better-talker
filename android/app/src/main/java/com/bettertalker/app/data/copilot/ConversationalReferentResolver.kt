package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.util.normalizeText

/**
 * PEDAGOGY-0A — Resolvedor determinístico de referente conversacional.
 *
 * Pedidos de aprofundamento curtos ("Explique melhor", "Não entendi",
 * "Dá outro exemplo") não carregam o referente explícito. O resolvedor
 * resolve quem o pedido refere, usando o contexto conversacional:
 *
 * - `DeepenCard` — aprofundar o último card;
 * - `ExplainSuggestion` — explicar a última sugestão;
 * - `ExplainActiveSection` — explicar o tópico ativo;
 * - `RequestClarification` — não há referente inequívoco (peça esclarecimento);
 * - `ProceedNormal` — não é pedido de aprofundamento (fluxo conversacional normal).
 *
 * Saídas tipadas (P2): Aprofundar o card / Explicar a última sugestão /
 * Explicar o tópico ativo / Solicitar esclarecimento / Prosseguir pelo
 * fluxo conversacional normal.
 *
 * Puro/testável: sem UI, Room, rede ou LLM. Não presume que toda mensagem
 * curta é solicitação simples — pedidos sem verbo de aprofundamento
 * devolvem `ProceedNormal`.
 */
object ConversationalReferentResolver {

    /** Estado do foco conversacional (P2 — entrada do resolvedor). */
    enum class Focus { SECTION, WHOLE }

    /**
     * Estado do conversado para a resolução do referente (P2): identidade
     * da nota, seção ativa, último card ou resposta pertinente, última
     * sugestão, estado do foco conversacional.
     */
    data class Context(
        val noteId: String?,
        val chatScope: String?,
        val focus: Focus? = null,
        val lastCard: CardPayload? = null,
        val lastSuggestion: NextStepSuggestion? = null,
        val lastResponse: String? = null,
    )

    /** Saídas tipadas (P2). */
    sealed interface Referent {
        /** Aprofundar o card (último card pertinente). */
        data class DeepenCard(val card: CardPayload) : Referent

        /** Explicar a última sugestão. */
        data class ExplainSuggestion(val suggestion: NextStepSuggestion) : Referent

        /** Explicar o tópico ativo (seção do chat). */
        data class ExplainActiveSection(val sectionId: String) : Referent

        /** Não há referente inequívoco — pedir esclarecimento ao usuário. */
        data class RequestClarification(val reason: String) : Referent

        /** Prosseguir pelo fluxo conversacional normal (não é de aprofundamento). */
        data object ProceedNormal : Referent
    }

    /** Pedidos de aprofundimento curtos sem verbo de ação explícito. */
    private val DEEPEN = Regex(
        """\b(
            explique[\s]+melhor|explicar[\s]+melhor|
            nao[\s]+entendi|nao[\s]+compreendi|
            d[ae][\s]+outro[\s]+exemplo|outros[\s]+exemplos|exemplo[\s]+diferente|
            vamos[\s]+continuar|continuar[\s]+de[\s]+onde|fale[\s]+mais
        )\b"""
            .trimIndent()
            .replace("\n", " ")
    )

    /** Pedidos explícitos de rascunho ("Desenvolva o ponto 1") — fluxo normal. */
    private fun isExplicitDraftRequest(t: String): Boolean =
        Regex("""\b(desenvolv|escrev|redig)\b""").containsMatchIn(t)

    /**
     * Resolve o referente do pedido atual. [request] = texto do pedido do
     * usuário; [context] = estado conversacional.
     */
    fun resolve(request: String, context: Context): Referent {
        val t = normalizeText(request)
        if (t.isBlank()) return Referent.RequestClarification("mensagem vazia")
        // Pedidos explícitos de rascunho não são aprofundamento: seguem
        // para o fluxo conversacional normal (ChatIntent Develop etc.).
        if (isExplicitDraftRequest(t)) return Referent.ProceedNormal
        if (!DEEPEN.containsMatchIn(t)) return Referent.ProceedNormal
        // Referência explícita ao referente (card/sugestão citada no pedido).
        explicitReferent(t, context)?.let { return it }
        // Aprofundamento do último card — exceto troca de seção: o referente
        // correto é a seção ativa.
        if (context.lastCard != null) {
            if (context.chatScope != null &&
                context.lastCard.targetId?.takeIf { it.isNotBlank() } != null &&
                context.chatScope != context.lastCard.targetId
            ) {
                return Referent.ExplainActiveSection(context.chatScope)
            }
            return Referent.DeepenCard(context.lastCard)
        }
        if (context.lastSuggestion != null) return Referent.ExplainSuggestion(context.lastSuggestion)
        if (context.chatScope != null) return Referent.ExplainActiveSection(context.chatScope)
        return Referent.RequestClarification("não há referente inequívoco")
    }

    /**
     * Referência explícita citada no pedido: casa o card pelo título ou id
     * do último card, e a sugestão pelo título. Retorna null quando não casa.
     */
    private fun explicitReferent(t: String, context: Context): Referent? {
        context.lastCard?.let { card ->
            val label = card.targetLabel?.takeIf { it.isNotBlank() }
            val id = card.targetId?.takeIf { it.isNotBlank() }
            if ((label != null && t.contains(normalizeText(label))) ||
                (id != null && t.contains(normalizeText(id)))
            ) {
                return Referent.DeepenCard(card)
            }
        }
        context.lastSuggestion?.let { s ->
            if (s.label.isNotBlank() && t.contains(normalizeText(s.label))) {
                return Referent.ExplainSuggestion(s)
            }
        }
        return null
    }
}
