package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.data.util.normalizeText

/**
 * Fase 20-C — continuidade da geração oratória (iteração + troca de alvo).
 *
 * Não é uma arquitetura nova: é a camada que decide QUAL modo e QUAL ponto
 * um pedido em linguagem natural quer, lembrando a última geração da sessão.
 *
 * Regras (§§16-20, 31-34):
 * - pedido explícito de modo manda (troca de modo/ponto);
 * - refinamento ("melhore", "deixe mais natural", "encurte", "explique
 *   melhor") HERDA modo + ponto da última geração — nunca volta ao começo;
 * - refinamento sem geração anterior → estado amigável (não inventa alvo);
 * - "crie um ponto 4" → fora do escopo estrutural (nunca vira S-34);
 * - a estrutura do S-34 continua sendo a única fonte de pontos.
 *
 * Puro/testável: sem rede, LLM, banco, UI. O estado de sessão é um valor
 * imutável que o chamador guarda (mesmo padrão da F15/F17).
 */
object OratorySession {

    /** Mensagem padrão de refinamento sem geração anterior. */
    const val NOTHING_TO_REFINE_MESSAGE =
        "Ainda não gerei nenhuma parte. Peça, por exemplo, \"Crie uma introdução\" " +
            "ou \"Desenvolva o ponto 2\"."

    /** Mensagem de pedido que tentaria criar estrutura nova. */
    fun outOfStructuralScopeMessage(point: Int): String =
        "Não posso criar um ponto $point: a estrutura vem do S-34. " +
            "Posso desenvolver um dos pontos existentes ou ajustar o texto deles."


    /** Última geração oratória da sessão (volátil por design). */
    data class LastGeneration(val mode: OratoryGeneration.Mode, val sectionId: String?)

    sealed interface Decision {
        /** Gera com este modo/ponto. */
        data class Generate(
            val mode: OratoryGeneration.Mode,
            val sectionId: String?,
            val action: OratoryGeneration.Action,
            val inherited: Boolean
        ) : Decision

        /** Refinamento sem geração anterior: nada a refinar. */
        data object NothingToRefine : Decision {
            val message: String = NOTHING_TO_REFINE_MESSAGE
        }

        /** Pedido cria estrutura nova (ponto inexistente): fora do escopo. */
        data class OutOfStructuralScope(val requestedPoint: Int) : Decision {
            val message: String = outOfStructuralScopeMessage(requestedPoint)
        }
    }

    /** Modos pedidos explicitamente (qualquer um deles vence herança). */
    fun explicitMode(text: String): OratoryGeneration.Mode? = OratoryGeneration.detectMode(text)

    /** Refinamento: pedido que se apoia numa geração anterior. */
    fun isRefinement(text: String): Boolean {
        val t = " ${normalizeText(text)} "
        return Regex(
            "(melhore|melhorar|melhor|refaca|refazer|reescreva|ajuste|corrija|deixe mais|" +
                "mais natural|mais curta|encurte|encurtar|resuma|mais curto|explique melhor|" +
                "aprofunde|detalhe|mais simples|mais direto)"
        ).containsMatchIn(t)
    }

    /** Número de ponto pedido ("ponto 3", "3.", "no 3") — 1-based. */
    fun requestedPoint(text: String): Int? {
        val t = " ${normalizeText(text)} "
        Regex("(ponto|topico|secao|parte)\\s*(n\\.?\\u00ba?\\s*)?(\\d{1,2})").find(t)?.let {
            return it.groupValues[3].toIntOrNull()
        }
        Regex("\\b(\\d{1,2})\\b").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** Pedido que tenta criar estrutura nova ("crie um ponto 4"). */
    fun triesToCreateStructure(text: String, existingPoints: Int): Int? {
        val t = " ${normalizeText(text)} "
        val criar = Regex("(crie|criar|adicione|adicionar|invente|inclua|inserir)").containsMatchIn(t)
        val ponto = Regex("(ponto|topico|secao|parte)").containsMatchIn(t)
        if (!criar || !ponto) return null
        val n = requestedPoint(text) ?: return null
        return if (n > existingPoints) n else null
    }

    /**
     * Decide o que fazer com o pedido. `currentSectionId` vem da B.4 (foco
     * real); `last` é a última geração da sessão.
     */
    fun decide(
        text: String,
        document: S34Document?,
        currentSectionId: String?,
        last: LastGeneration?
    ): Decision {
        val points = document?.sections?.size ?: 0
        // 1) Tentativa de criar estrutura: nunca vira ponto do S-34.
        triesToCreateStructure(text, points)?.let { return Decision.OutOfStructuralScope(it) }

        // 2) Modo explícito manda (troca de modo/ponto).
        explicitMode(text)?.let { mode ->
            val sectionId = sectionFor(text, document, currentSectionId, mode)
            return Decision.Generate(
                mode = mode,
                sectionId = sectionId,
                action = OratoryGeneration.actionFor(text),
                inherited = false
            )
        }

        // 3) Refinamento herda modo + ponto da última geração.
        if (isRefinement(text)) {
            if (last == null) return Decision.NothingToRefine
            return Decision.Generate(
                mode = last.mode,
                sectionId = last.sectionId,
                action = OratoryGeneration.Action.REPLACE,
                inherited = true
            )
        }

        return Decision.NothingToRefine
    }

    /**
     * Ponto do pedido: número explícito ("ponto 3") tem precedência; senão o
     * ponto atual; introdução/conclusão usam as pontas do esboço.
     */
    /**
     * F20-E (§11/§13): a transição tem DOIS pontos. Ancorar no ponto de
     * DESTINO ("transição para o ponto 3") quebrava a geração no fim do
     * esboço e não seguia o contrato "2→3". A âncora é sempre a ORIGEM:
     * - "do ponto 2 para o 3" / "transição do ponto 2" → sec-2;
     * - "transição para o ponto 3" → sec-2 (o ponto imediatamente anterior);
     * - sem ponto explícito → ponto atual da sessão (comportamento F20-C).
     */
    private fun transitionAnchor(text: String, ordered: List<S34Section>): Pair<Boolean, String?> {
        val t = " ${normalizeText(text)} "
        val pointWord = "(?:ponto|topico|secao|parte)"
        val from = Regex("(?:^|\\s)(?:do|de|desde)\\s+(?:o\\s+|a\\s+)?$pointWord\\s*(\\d{1,2})")
            .find(t)
        if (from != null) {
            val n = from.groupValues[1].toInt()
            return true to ordered.getOrNull(n - 1)?.id
        }
        val to = Regex("(?:^|\\s)(?:para|ate|ao|a)\\s+(?:o\\s+|a\\s+)?$pointWord\\s*(\\d{1,2})")
            .find(t)
        if (to != null) {
            val n = to.groupValues[1].toInt()
            // Destino N: a transição sai do ponto N-1.
            return true to ordered.getOrNull(n - 2)?.id
        }
        return false to null
    }

    private fun sectionFor(
        text: String,
        document: S34Document?,
        currentSectionId: String?,
        mode: OratoryGeneration.Mode
    ): String? {
        val ordered = document?.sections?.sortedBy { it.order } ?: return currentSectionId
        if (ordered.isEmpty()) return null
        if (mode == OratoryGeneration.Mode.TRANSITION) {
            val (matched, anchor) = transitionAnchor(text, ordered)
            if (matched) return anchor
        }
        requestedPoint(text)?.let { n ->
            ordered.getOrNull(n - 1)?.let { return it.id }
        }
        return when (mode) {
            OratoryGeneration.Mode.INTRODUCTION -> ordered.first().id
            OratoryGeneration.Mode.CONCLUSION -> ordered.last().id
            else -> currentSectionId
        }
    }

    /** Registra a geração para as próximas iterações. */
    fun remember(
        decision: Decision.Generate,
        document: S34Document?
    ): LastGeneration {
        // Introdução/conclusão fixam as pontas; desenvolvimento/transição
        // mantêm o ponto resolvido.
        return LastGeneration(decision.mode, decision.sectionId)
    }
}
