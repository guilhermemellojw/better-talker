package com.bettertalker.app.data.copilot

import kotlinx.coroutines.CancellationException

/**
 * 1C.2.2B0 — resultado da memória canônica pronta para o prompt.
 *
 * O bloco é texto de orientação, nunca evidência factual. [available] fica
 * falso quando o ledger não pôde ser lido; nesse caso o chamador deve suprimir
 * o legado em vez de voltar silenciosamente ao `agreedApproach`.
 */
data class DecisionPromptMemoryResult(
    val block: String?,
    val available: Boolean,
    val hasLedgerRecords: Boolean,
    val suppressLegacyApproach: Boolean,
    val includedCount: Int,
    val omittedCount: Int,
    val noteCount: Int,
    val sectionCount: Int,
    val staleCount: Int,
    val characters: Int,
) {
    init {
        require(characters >= 0) { "characters negativo" }
        require(block == null || characters == block.length) {
            "characters deve corresponder ao bloco"
        }
        require(block != null || includedCount == 0) {
            "bloco nulo não pode ter decisões incluídas"
        }
    }
}

/**
 * 1C.2.2B0 — seleção e renderização determinística das decisões confirmadas.
 *
 * Regra central: somente o ledger é autoridade. `agreedApproach` e o resumo
 * rolante não são consultados aqui. A seleção usa IDs persistidos e escopo,
 * nunca similaridade textual; a deduplicação só remove registros com o mesmo
 * escopo, seção e texto normalizado.
 */
object DecisionPromptMemory {

    /** Teto do bloco no chat normal. O modo colar usa um teto menor. */
    const val MAX_CHAT_CHARS = 1400

    private const val HEADER =
        "## DECISÕES CONFIRMADAS PELO USUÁRIO\n" +
            "Estas decisões estão ativas e orientam a redação. " +
            "Não são fonte factual nem prova de textos bíblicos."
    private const val APPLICATION_HEADER = "## ESTADO DA APLICAÇÃO AO TEXTO"

    /**
     * Monta o bloco para a nota e o foco informados.
     *
     * - [wholeNote] = visão da nota inteira: NOTE + todas as seções elegíveis,
     *   cada decisão local identificada pela seção de origem.
     * - [focusSectionId] presente: NOTE + decisões exatamente dessa seção.
     * - Sem foco e sem visão inteira: somente decisões NOTE (nunca vaza outra
     *   seção para o tópico atual).
     */
    fun build(
        decisions: List<DecisionRecord>,
        noteId: String,
        focusSectionId: String?,
        wholeNote: Boolean,
        maxChars: Int = MAX_CHAT_CHARS,
    ): DecisionPromptMemoryResult {
        require(noteId.isNotBlank()) { "noteId vazio" }
        require(maxChars > 0) { "maxChars deve ser positivo" }

        val noteRecords = decisions.filter { it.noteId == noteId }
        val hasRecords = noteRecords.isNotEmpty()
        val focus = focusSectionId?.takeIf { it.isNotBlank() }

        val scoped = noteRecords
            .filter { it.isEligibleForPromptMemory() }
            .filter { decision ->
                when {
                    decision.scope == DecisionScope.NOTE -> true
                    wholeNote -> true
                    focus != null -> decision.sectionId == focus
                    else -> false
                }
            }

        // Dedupe conservador: mesmo escopo + mesma seção + mesmo texto
        // normalizado. Mantém o registro mais recente de forma determinística.
        val ordered = scoped
            .groupBy { Triple(it.scope, it.sectionId, normalizeForDedupe(it.text)) }
            .values
            .mapNotNull { group ->
                group.maxWithOrNull(
                    compareBy<DecisionRecord>(
                        { it.updatedAt },
                        { it.createdAt },
                        { it.id },
                    )
                )
            }
            .sortedWith(
                compareBy<DecisionRecord>(
                    { if (it.scope == DecisionScope.NOTE) 0 else 1 },
                    { if (focus != null && it.sectionId == focus) 0 else 1 },
                    { it.sectionOrderSnapshot ?: -1 },
                    { it.createdAt },
                    { it.id },
                )
            )

        // Nunca trunca uma decisão pela metade: inclui apenas pares completos
        // (decisão + estado da aplicação) que cabem inteiros no orçamento.
        val selected = mutableListOf<DecisionRecord>()
        for (decision in ordered) {
            val candidate = selected + decision
            if (render(candidate, focus).length <= maxChars) {
                selected += decision
            }
        }

        if (selected.isEmpty()) {
            return result(
                block = null,
                available = true,
                hasRecords = hasRecords,
                suppressLegacyApproach = hasRecords,
                included = emptyList(),
                omitted = ordered.size,
            )
        }
        val block = render(selected, focus)
        return result(
            block = block,
            available = true,
            hasRecords = hasRecords,
            suppressLegacyApproach = hasRecords,
            included = selected,
            omitted = ordered.size - selected.size,
        )
    }

    /** Nenhum ledger aplicável (sem nota): não suprime o legado. */
    fun notApplicable(): DecisionPromptMemoryResult = result(
        block = null,
        available = true,
        hasRecords = false,
        suppressLegacyApproach = false,
        included = emptyList(),
        omitted = 0,
    )

    /** Leitura falhou: sem bloco e com supressão conservadora do legado. */
    fun unavailable(): DecisionPromptMemoryResult = result(
        block = null,
        available = false,
        hasRecords = false,
        suppressLegacyApproach = true,
        included = emptyList(),
        omitted = 0,
    )

    private fun result(
        block: String?,
        available: Boolean,
        hasRecords: Boolean,
        suppressLegacyApproach: Boolean,
        included: List<DecisionRecord>,
        omitted: Int,
    ) = DecisionPromptMemoryResult(
        block = block,
        available = available,
        hasLedgerRecords = hasRecords,
        suppressLegacyApproach = suppressLegacyApproach,
        includedCount = included.size,
        omittedCount = omitted,
        noteCount = included.count { it.scope == DecisionScope.NOTE },
        sectionCount = included.count { it.scope == DecisionScope.SECTION },
        staleCount = included.count { it.applicationState == ApplicationState.STALE },
        characters = block?.length ?: 0,
    )

    private fun render(
        decisions: List<DecisionRecord>,
        focusSectionId: String?,
    ): String {
        if (decisions.isEmpty()) return ""
        return buildString {
            appendLine(HEADER)
            decisions.forEachIndexed { index, decision ->
                appendLine(
                    "${index + 1}. ${label(decision, focusSectionId)} " +
                        oneLine(decision.text),
                )
            }
            appendLine()
            appendLine(APPLICATION_HEADER)
            decisions.forEachIndexed { index, decision ->
                appendLine("${index + 1}. ${applicationPhrase(decision.applicationState)}")
            }
        }.trim()
    }

    private fun label(decision: DecisionRecord, focusSectionId: String?): String =
        when (decision.scope) {
            DecisionScope.NOTE -> "[Nota]"
            DecisionScope.SECTION -> {
                val title = decision.sectionTitleSnapshot
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "Seção"
                if (decision.sectionId == focusSectionId) {
                    "[Seção em foco: $title]"
                } else {
                    "[Seção: $title]"
                }
            }
        }

    private fun applicationPhrase(state: ApplicationState): String = when (state) {
        ApplicationState.NOT_APPLIED -> "ainda não aplicada ao texto."
        ApplicationState.APPLIED -> "aplicada ao texto."
        ApplicationState.STALE ->
            "aplicação desatualizada: o texto mudou depois da aplicação."
    }

    private fun oneLine(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    private fun normalizeForDedupe(text: String): String =
        oneLine(text).lowercase()
}

/**
 * Falha de leitura nunca vira memória inventada nem fallback inseguro: retorna
 * [DecisionPromptMemory.unavailable] e o chamador suprime o `agreedApproach`.
 * Cancelamento de coroutine continua propagando.
 */
suspend fun loadDecisionPromptMemory(
    noteId: String?,
    focusSectionId: String?,
    wholeNote: Boolean,
    maxChars: Int = DecisionPromptMemory.MAX_CHAT_CHARS,
    load: suspend (String) -> List<DecisionRecord>,
): DecisionPromptMemoryResult {
    val nid = noteId?.takeIf { it.isNotBlank() }
        ?: return DecisionPromptMemory.notApplicable()
    return try {
        DecisionPromptMemory.build(
            decisions = load(nid),
            noteId = nid,
            focusSectionId = focusSectionId,
            wholeNote = wholeNote,
            maxChars = maxChars,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        DecisionPromptMemory.unavailable()
    }
}
