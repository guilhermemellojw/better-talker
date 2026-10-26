package com.bettertalker.app.domain.planning

/**
 * C1 — orçamento de saída por caminho.
 *
 * Fonte única dos tetos de `max_tokens`. Cada caminho tem uma função nomeada:
 * o provider nunca recebe o default histórico de 1.000 tokens em silêncio.
 *
 * Regras:
 * - o orçamento é proporcional ao trabalho (palavras, réplicas, foco, seções);
 * - todo caminho tem piso e teto explícitos;
 * - acima do teto de uma resposta única, [structure] devolve `parts > 1` para
 *   o chamador dividir o trabalho antes de estourar o limite.
 *
 * Puro/testável: sem Android, sem IO.
 */
object OutputBudget {

    /** Piso do "Continuar gerando" (resposta curta que foi cortada). */
    const val CONTINUE_MIN = 1024

    /** Teto absoluto do provedor (DeepSeek). */
    const val MAX = 8192

    /** Chat livre sem fontes resolvidas. */
    const val CHAT = 2048

    /** Chat livre com fontes/refs resolvidas (citação + comentário). */
    const val CHAT_WITH_SOURCES = 4096

    /** Proposta de edição: piso e teto (JSON cabe no OTPM do plano). */
    const val PROPOSAL_MIN = 1500
    const val PROPOSAL_MAX = 4096

    /** Estrutura completa da nota (Markdown canônico). */
    const val STRUCTURE_MIN = 4096
    const val STRUCTURE_MAX = 8192

    /** Acima deste total, a estrutura é gerada em partes. */
    const val STRUCTURE_SPLIT_MINUTES = 30

    /** Conversa sobre esboço colado (modo minimalista). */
    const val MINIMAL_MIN = 2048
    const val MINIMAL_MAX = 4096

    /** Oratória/proposta JSON de uma seção. */
    const val ORATORY_MIN = 1280
    const val ORATORY_MAX = 4096

    /** Esboço inicial (JSON de títulos): fixo para caber no TPM de 8K. */
    const val OUTLINE = 1500

    /** Draft de seção: piso e teto. Sem tempo conhecido, fallback histórico. */
    const val DRAFT_MIN = 2048
    const val DRAFT_MAX = 8192
    const val DRAFT_UNKNOWN_MINUTES = 500

    /** Tribuna (JSON de cápsulas por seção). */
    const val TRIBUNA_MIN = 2048
    const val TRIBUNA_MAX = 4096

    /** Chat: mais espaço quando a resposta cita fontes resolvidas. */
    fun chat(hasResolvedRefs: Boolean): Int =
        if (hasResolvedRefs) CHAT_WITH_SOURCES else CHAT

    /**
     * "Continuar gerando": quanto falta para a meta, não o tamanho da resposta
     * anterior. Com meta conhecida (texto longo), usa palavras/réplicas
     * restantes; sem meta, usa o tamanho atual como sinal.
     */
    fun continueGeneration(
        currentText: String,
        targetWords: Int? = null,
        targetTurns: Int? = null,
    ): Int {
        val byTarget = when {
            targetTurns != null && targetTurns > 0 -> {
                val remaining = (targetTurns - cardTurnCount(currentText)).coerceAtLeast(1)
                remaining * 80 + 200
            }
            targetWords != null && targetWords > 0 -> {
                val remaining = (targetWords - cardWordCount(currentText)).coerceAtLeast(1)
                remaining * 3 / 2 + 200
            }
            else -> {
                // ~3 caracteres por token; metade do já produzido + folga.
                val estimatedTokens = (currentText.length + 2) / 3
                estimatedTokens + 512
            }
        }
        return byTarget.coerceIn(CONTINUE_MIN, MAX)
    }

    /**
     * Proposta de edição: foco + número estimado de operações.
     * Cada operação carrega o bloco de foco e devolve o texto novo; o JSON
     * consome ~400 tokens além do conteúdo.
     */
    fun proposal(focusChars: Int, operations: Int = 1): Int {
        val focusTokens = (focusChars.coerceAtLeast(0) + 3) / 4
        val ops = operations.coerceAtLeast(1)
        val raw = 700 + ops * (focusTokens + 400)
        return raw.coerceIn(PROPOSAL_MIN, PROPOSAL_MAX)
    }

    /**
     * Estrutura completa: usa o total de minutos e o número de seções.
     * Acima de [STRUCTURE_SPLIT_MINUTES], força pelo menos 2 partes.
     */
    fun structure(
        totalMinutes: Int?,
        sectionCount: Int,
        sourceChars: Int = 0,
    ): StructureBudget {
        val minutes = totalMinutes?.takeIf { it > 0 } ?: 0
        val byMinutes = if (minutes > 0) {
            SpeechPacing.targetWords(minutes) * 3 / 2 + 200
        } else {
            0
        }
        val bySections = sectionCount.coerceAtLeast(0) * 450
        val bySource = if (sourceChars > 0) sourceChars / 3 else 0
        val raw = maxOf(byMinutes, bySections, bySource).coerceAtLeast(1)
        val byCap = (raw + MAX - 1) / MAX
        val mustSplit = minutes > STRUCTURE_SPLIT_MINUTES
        val parts = maxOf(if (mustSplit) 2 else 1, byCap)
        val perPart = ((raw + parts - 1) / parts).coerceIn(STRUCTURE_MIN, STRUCTURE_MAX)
        return StructureBudget(
            maxTokens = perPart,
            parts = parts,
            rawTokens = raw,
            totalMinutes = minutes,
            sectionCount = sectionCount.coerceAtLeast(0),
        )
    }

    /** Divide títulos em partes equilibradas (vazio quando não há títulos). */
    fun splitStructureTitles(titles: List<String>, parts: Int): List<List<String>> {
        if (titles.isEmpty()) return emptyList()
        val n = parts.coerceAtLeast(1).coerceAtMost(titles.size)
        if (n <= 1) return listOf(titles)
        val base = titles.size / n
        val extra = titles.size % n
        var index = 0
        return (0 until n).map { i ->
            val size = base + if (i < extra) 1 else 0
            val chunk = titles.subList(index, index + size).toList()
            index += size
            chunk
        }.filter { it.isNotEmpty() }
    }

    /**
     * Conversa sobre esboço colado. Texto completo NÃO é gerado por este
     * caminho: o chamador encaminha para o DraftSheet e este teto fica no
     * limite de segurança.
     */
    fun minimal(pastedChars: Int, asksForFullText: Boolean = false): Int {
        if (asksForFullText) return MINIMAL_MAX
        val raw = MINIMAL_MIN + pastedChars.coerceAtLeast(0) / 16
        return raw.coerceIn(MINIMAL_MIN, MINIMAL_MAX)
    }

    /**
     * Oratória (JSON de uma operação): usa a duração da seção; sem duração,
     * usa o tamanho do foco. Substitui o 1.280 fixo.
     */
    fun oratory(focusChars: Int, minutes: Int?): Int {
        val words = minutes?.takeIf { it > 0 }?.let { SpeechPacing.targetWords(it) }
        val raw = if (words != null) {
            words * 3 / 2 + 200
        } else {
            focusChars.coerceAtLeast(0) / 3 + 600
        }
        return raw.coerceIn(ORATORY_MIN, ORATORY_MAX)
    }

    /** Esboço inicial: fixo em 1.500 para caber no TPM de 8K do plano. */
    fun outline(totalMinutes: Int = 0, sectionCount: Int = 0): Int = OUTLINE

    /**
     * Draft de seção. Usa a duração da SEÇÃO (ou do sub-ponto, quando a seção
     * longa foi dividida) e nunca o total do discurso.
     */
    fun sectionDraft(minutes: Int?, kind: LongTextKind = LongTextKind.SPEECH): Int {
        val safe = minutes?.takeIf { it > 0 } ?: return DRAFT_UNKNOWN_MINUTES
        val raw = if (kind == LongTextKind.DEMO) {
            SpeechPacing.targetTurns(safe) * 80 + 200
        } else {
            SpeechPacing.targetWords(safe) * 3 / 2 + 200
        }
        return raw.coerceIn(DRAFT_MIN, DRAFT_MAX)
    }

    /** Tribuna: um JSON pequeno por seção; cresce com o número de seções. */
    fun tribuna(sectionCount: Int): Int =
        (TRIBUNA_MIN + sectionCount.coerceAtLeast(0) * 80).coerceIn(TRIBUNA_MIN, TRIBUNA_MAX)

    /** Pedido longo: mesma fórmula já validada do texto longo. */
    fun longText(request: LongTextRequest): LongTextBudget = budgetForRequest(request)
}

/** Orçamento de uma estrutura que pode precisar de várias chamadas. */
data class StructureBudget(
    val maxTokens: Int,
    val parts: Int,
    val rawTokens: Int,
    val totalMinutes: Int,
    val sectionCount: Int,
)
