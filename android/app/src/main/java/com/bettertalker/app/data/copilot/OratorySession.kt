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
    data class LastGeneration(
        val mode: OratoryGeneration.Mode,
        val sectionId: String?,
        /** Ponto relacionado pedido junto (ex.: AI-04); preservado na iteração. */
        val relatedSectionId: String? = null,
    )

    sealed interface Decision {
        /** Gera com este modo/ponto. */
        data class Generate(
            val mode: OratoryGeneration.Mode,
            val sectionId: String?,
            val action: OratoryGeneration.Action,
            val inherited: Boolean,
            /** Ponto citado para relação explícita (conteúdo real no prompt). */
            val relatedSectionId: String? = null,
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
        // Limites de palavra são obrigatórios: "melhoria"/"melhorias" contêm
        // "melhor" como substring e antes viravam refinamento oratório,
        // sequestrando a discussão de um card ("Não concordo com a segunda
        // melhoria. Por quê?").
        return REFINEMENT.containsMatchIn(t)
    }

    private val REFINEMENT = Regex(
        "\\b(melhore|melhorar|refaca|refazer|reescreva|ajuste|corrija|" +
            "encurte|encurtar|resuma|aprofunde|detalhe)\\b|" +
            "\\b(deixe mais|deixe melhor|mais natural|mais curta|mais curto|" +
            "mais simples|mais direto|explique melhor)\\b"
    )

    private val POINT_REFERENCE = Regex(
        "(pontos?|topicos?|secoes?|partes?)\\s*(n\\.?\\u00ba?\\s*)?(\\d{1,2})"
    )
    private val RELATION_VERB = Regex("\\b(relacion\\w*)\\b")
    private val TRANSITION_MARK = Regex("\\b(transic\\w*|passagem|ponte)\\b")

    /**
     * Ponto citado DEPOIS de um verbo de relação:
     * "relacione o ponto 2 com o ponto 3" → 3;
     * "transição 2→3 relacionando com o ponto 1" → 1.
     */
    private val RELATION_WITH = Regex(
        "\\brelacion\\w*\\b[^\\n]{0,80}?" +
            "\\b(?:com|ao|a|e)\\s+(?:o\\s+|a\\s+)?(?:ponto|topico|secao|parte)\\s*(\\d{1,2})"
    )

    private data class PointRelation(val anchorId: String, val relatedId: String)

    /**
     * Todos os pontos citados, na ordem do texto. Diferente de
     * [requestedPoint], preserva as duas pontas de "ponto 1 com o ponto 3".
     */
    fun requestedPoints(text: String): List<Int> {
        val t = " ${normalizeText(text)} "
        return POINT_REFERENCE.findAll(t)
            .mapNotNull { it.groupValues[3].toIntOrNull() }
            .toList()
    }

    /**
     * Número de ponto pedido ("ponto 3", "3.", "no 3") — 1-based.
     *
     * O fallback numérico é deliberadamente conservador: texto com `§`,
     * "parágrafo" ou referência cap:vers NÃO é um ponto. Antes,
     * `w17.10 23 §§9-10` virava "ponto 10" e o S-34 mudava de alvo.
     */
    fun requestedPoint(text: String): Int? {
        val t = " ${normalizeText(text)} "
        Regex("(ponto|topico|secao|parte)\\s*(n\\.?\\u00ba?\\s*)?(\\d{1,2})").find(t)?.let {
            return it.groupValues[3].toIntOrNull()
        }
        val raw = text.lowercase()
        if (raw.contains('§') || raw.contains("parag") || raw.contains(':')) return null
        val trimmed = t.trim().trim('.', '!', '?', ',', ';')
        val numeric = Regex("^(?:no|numero|n\\.?)?\\s*(\\d{1,2})$").find(trimmed)
            ?: return null
        return numeric.groupValues[1].toIntOrNull()
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
            // AI-03: "transição 2→3 relacionando com o ponto 1" mantém o
            // modo TRANSITION, mas leva o CONTEÚDO real do ponto 1.
            val related = sectionId?.let { anchor ->
                document?.sections
                    ?.sortedBy { it.order }
                    ?.let { ordered -> relatedSectionFor(text, ordered, anchor) }
            }
            return Decision.Generate(
                mode = mode,
                sectionId = sectionId,
                action = OratoryGeneration.actionFor(text),
                inherited = false,
                relatedSectionId = related,
            )
        }

        // 2b) AI-04 — relação explícita entre dois pontos sem verbo de
        // geração: "volte ao ponto 1 e relacione com o ponto 3". Vira
        // desenvolvimento do ponto-âncora com o outro ponto no prompt.
        relationRequest(text, document, currentSectionId)?.let { relation ->
            return Decision.Generate(
                mode = OratoryGeneration.Mode.DEVELOPMENT,
                sectionId = relation.anchorId,
                action = OratoryGeneration.actionFor(text),
                inherited = false,
                relatedSectionId = relation.relatedId,
            )
        }

        // 3) Refinamento herda modo + ponto da última geração.
        if (isRefinement(text)) {
            if (last == null) return Decision.NothingToRefine
            return Decision.Generate(
                mode = last.mode,
                sectionId = last.sectionId,
                action = OratoryGeneration.Action.REPLACE,
                inherited = true,
                relatedSectionId = last.relatedSectionId,
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

    /** Ponto citado para relação, quando o modo já foi decidido (AI-03). */
    private fun relatedSectionFor(
        text: String,
        ordered: List<S34Section>,
        anchorId: String,
    ): String? {
        val n = RELATION_WITH.find(" ${normalizeText(text)} ")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        return ordered.getOrNull(n - 1)?.id?.takeIf { it != anchorId }
    }

    /**
     * Relação explícita entre pontos (AI-04). Não intercepta transição:
     * "transição ... relacionando com o ponto N" continua TRANSITION, com
     * o ponto N como `related`.
     */
    private fun relationRequest(
        text: String,
        document: S34Document?,
        currentSectionId: String?,
    ): PointRelation? {
        val t = " ${normalizeText(text)} "
        if (TRANSITION_MARK.containsMatchIn(t)) return null
        if (!RELATION_VERB.containsMatchIn(t)) return null
        val ordered = document?.sections?.sortedBy { it.order } ?: return null
        if (ordered.isEmpty()) return null
        val numbers = requestedPoints(text)
        val relationNumber = RELATION_WITH.find(t)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val anchorNumber = numbers.firstOrNull { it != relationNumber }
            ?: numbers.firstOrNull()
        val relatedNumber = relationNumber ?: numbers.getOrNull(1)
        val anchorId = anchorNumber
            ?.let { n -> ordered.getOrNull(n - 1)?.id }
            ?: currentSectionId
            ?: return null
        val relatedId = relatedNumber
            ?.let { n -> ordered.getOrNull(n - 1)?.id }
            ?: return null
        if (anchorId == relatedId) return null
        return PointRelation(anchorId, relatedId)
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
        // mantêm o ponto resolvido e a relação explícita (AI-04).
        return LastGeneration(
            decision.mode,
            decision.sectionId,
            decision.relatedSectionId,
        )
    }
}
