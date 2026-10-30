package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.DiscourseType
import java.util.Locale

/**
 * Pedido de texto longo (discurso ou demonstração) com o tempo correto.
 *
 * Regra de ouro: a geração de UMA seção usa [sectionMinutes], nunca o total do
 * discurso. Um pedido explícito de outra duração é sinalizado para o usuário
 * antes de gerar (ver [LongTextResolution.NeedsConfirmation]).
 *
 * Puro/testável: sem Android, sem IO.
 */
enum class LongTextKind { SPEECH, DEMO }

enum class LongTextScope { SECTION, TOTAL }

data class LongTextRequest(
    val kind: LongTextKind,
    val scope: LongTextScope,
    /** Duração efetiva do texto pedido (seção ou total). */
    val minutes: Int,
    /** Duração da seção em foco; base para o padrão e para a soma do discurso. */
    val sectionMinutes: Int? = null,
    /** Meta de palavras para discurso; 0 em demonstração. */
    val targetWords: Int = if (kind == LongTextKind.SPEECH) {
        SpeechPacing.targetWords(minutes)
    } else {
        0
    },
    /** Meta de réplicas para demonstração; 0 em discurso. */
    val targetTurns: Int = if (kind == LongTextKind.DEMO) {
        SpeechPacing.targetTurns(minutes)
    } else {
        0
    },
    /** Minutos pedidos explicitamente pelo usuário (null = usou o padrão). */
    val requestedMinutes: Int? = null,
    /** 1-based quando este pedido é uma parte interna (45 min monolítico). */
    val partIndex: Int? = null,
    /** Total de partes internas; null/1 = pedido único. */
    val partCount: Int? = null,
    /** Correção de extensão: sobe o teto desta chamada até 8.192. */
    val maxTokensOverride: Int? = null,
) {
    val isDemo: Boolean get() = kind == LongTextKind.DEMO

    /** O pedido explícito difere do tempo da seção em foco. */
    val differsFromSection: Boolean
        get() = requestedMinutes != null &&
            sectionMinutes != null &&
            requestedMinutes != sectionMinutes
}

/** Resultado da resolução de um pedido: pronto ou precisa confirmar o tempo. */
sealed interface LongTextResolution {
    data class Ready(val request: LongTextRequest) : LongTextResolution

    data class NeedsConfirmation(
        val requested: LongTextRequest,
        val sectionMinutes: Int,
    ) : LongTextResolution
}

/**
 * Resolve o pedido com a regra "sectionMinutes como padrão".
 *
 * - [LongTextScope.SECTION]: usa os minutos da seção; um pedido explícito
 *   diferente vira [LongTextResolution.NeedsConfirmation] até o usuário
 *   confirmar (a soma do discurso não muda em silêncio).
 * - [LongTextScope.TOTAL]: o pedido explícito é o total; sem pedido, usa a
 *   duração da seção como referência.
 *
 * Retorna null quando não há duração válida.
 */
fun longTextRequestFor(
    kind: LongTextKind,
    scope: LongTextScope,
    requestedMinutes: Int? = null,
    sectionMinutes: Int? = null,
    confirmedDifferentMinutes: Boolean = false,
): LongTextResolution? {
    val section = sectionMinutes?.takeIf { it > 0 }
    val requested = requestedMinutes?.takeIf { it > 0 }
    val effective = when (scope) {
        LongTextScope.SECTION -> requested ?: section
        LongTextScope.TOTAL -> requested ?: section
    } ?: return null
    val minutes = effective.coerceIn(1, 90)
    val request = LongTextRequest(
        kind = kind,
        scope = scope,
        minutes = minutes,
        sectionMinutes = section,
        requestedMinutes = requested,
    )
    val needsConfirmation = scope == LongTextScope.SECTION &&
        requested != null &&
        section != null &&
        requested != section &&
        !confirmedDifferentMinutes
    return if (needsConfirmation) {
        LongTextResolution.NeedsConfirmation(request, section)
    } else {
        LongTextResolution.Ready(request)
    }
}

/** Teto de saída do DeepSeek para texto longo. */
const val LONG_TEXT_MAX_OUTPUT_TOKENS: Int = 8192

/** Fallback quando o endpoint rejeita 8192 (o chamador divide o trabalho). */
const val LONG_TEXT_FALLBACK_MAX_OUTPUT_TOKENS: Int = 4096

/**
 * Orçamento de saída de um pedido longo.
 *
 * Discurso: `palavras × 1,5 + 200`. Demonstração: `réplicas × 80 + 200`.
 * Coerção 2048..8192. Acima de 8192, divide em partes iguais (cada parte
 * volta à faixa 2048..8192).
 */
data class LongTextBudget(
    val maxTokens: Int,
    val parts: Int,
    val rawTokens: Int,
    val reasoningEffort: String = "none",
)

fun budgetForRequest(request: LongTextRequest): LongTextBudget {
    // Parte interna: o orçamento é o da SEÇÃO (ex.: 45 min → 4.488 por
    // parte), não o da fatia de minutos. O prompt usa os minutos da parte,
    // mas a saída precisa caber no teto de uma parte do total.
    val partCount = request.partCount?.takeIf { it > 1 }
    val budgetMinutes = if (partCount != null) {
        request.sectionMinutes?.takeIf { it > 0 } ?: request.minutes
    } else {
        request.minutes
    }
    val raw = if (request.kind == LongTextKind.DEMO) {
        // A parte usa o orçamento da SEÇÃO (45 min) e o divide pelo número
        // de partes; a meta de palavras da parte fica no prompt.
        val turns = if (partCount != null) {
            SpeechPacing.targetTurns(budgetMinutes)
        } else {
            request.targetTurns
        }
        turns.coerceAtLeast(1) * 80 + 200
    } else {
        val words = if (partCount != null) {
            SpeechPacing.targetWords(budgetMinutes)
        } else {
            request.targetWords
        }
        words.coerceAtLeast(1) * 3 / 2 + 200
    }
    val parts = partCount
        ?: ((raw + LONG_TEXT_MAX_OUTPUT_TOKENS - 1) / LONG_TEXT_MAX_OUTPUT_TOKENS)
            .coerceAtLeast(1)
    val perPart = request.maxTokensOverride
        ?.coerceIn(2048, LONG_TEXT_MAX_OUTPUT_TOKENS)
        ?: ((raw + parts - 1) / parts)
            .coerceIn(2048, LONG_TEXT_MAX_OUTPUT_TOKENS)
    return LongTextBudget(
        maxTokens = perPart,
        parts = parts,
        rawTokens = raw,
    )
}

/** Tipo de texto a partir do tipo persistido do discurso. */
fun longTextKindOf(discourseType: String?): LongTextKind =
    if (discourseType == DiscourseType.MINISTRY_PART.name ||
        discourseType == DiscourseType.FREE_DEMO.name
    ) {
        LongTextKind.DEMO
    } else {
        LongTextKind.SPEECH
    }

/** Minutos pedidos na mensagem ("5 min", "10 minutos"); null quando ausente. */
fun requestedMinutesIn(message: String): Int? =
    Regex("""\b(\d{1,2})\s*(?:min|minutos?)\b""", RegexOption.IGNORE_CASE)
        .find(message)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it in 1..90 }

/**
 * Aviso de tempo divergente: a seção manda na soma do discurso.
 * O chamador gera com [sectionMinutes] e deixa o ajuste para o editor.
 */
fun minutesMismatchNotice(sectionMinutes: Int, requestedMinutes: Int): String =
    String.format(
        Locale.ROOT,
        "Aviso: esta seção tem %d min, mas você pediu %d min. Vou manter %d min " +
            "para a soma do discurso. Para outro tempo, ajuste a duração da seção no editor.",
        sectionMinutes,
        requestedMinutes,
        sectionMinutes,
    )

// ---------- Verificação de extensão do card ----------

/**
 * Texto interno do card (ou o texto inteiro quando não há marcador).
 * Sem regex: um card de 10 min tem milhares de chars e o `.*?` do Java
 * estoura a pilha em textos longos de uma linha.
 */
fun cardTextOf(text: String): String {
    val open = "<texto-do-card>"
    val close = "</texto-do-card>"
    val start = text.indexOf(open)
    if (start < 0) return text
    val bodyStart = start + open.length
    val end = text.indexOf(close, bodyStart)
    return if (end < 0) text.substring(bodyStart) else text.substring(bodyStart, end)
}

/** Remove tags HTML sem regex (troca cada tag por um espaço). */
private fun stripHtmlTags(text: String): String {
    val out = StringBuilder(text.length)
    var inTag = false
    for (c in text) {
        when {
            c == '<' -> inTag = true
            c == '>' -> {
                inTag = false
                out.append(' ')
            }
            !inTag -> out.append(c)
        }
    }
    return out.toString()
}

/** Palavras do card (HTML não conta). */
fun cardWordCount(text: String): Int =
    stripHtmlTags(cardTextOf(text))
        .trim()
        .split(Regex("\\s+"))
        .count { it.isNotBlank() }

/** Réplicas do card de demonstração (`<b>Papel:</b>`), sem regex. */
fun cardTurnCount(text: String): Int {
    val card = cardTextOf(text)
    var count = 0
    var idx = card.indexOf("<b>")
    while (idx >= 0) {
        val close = card.indexOf(":</b>", idx)
        if (close < 0) break
        count++
        idx = card.indexOf("<b>", close + 5)
    }
    return count
}

/**
 * O card ficou abaixo de 80% da meta? Só vale para texto com marcador de
 * card; uma resposta conversacional curta não é "texto longo".
 */
fun isLongTextUnderTarget(text: String, request: LongTextRequest): Boolean {
    if (!text.contains("<texto-do-card>")) return false
    return if (request.kind == LongTextKind.DEMO) {
        cardTurnCount(text) < (request.targetTurns * 8 / 10).coerceAtLeast(1)
    } else {
        cardWordCount(text) < (request.targetWords * 8 / 10).coerceAtLeast(1)
    }
}

/**
 * Instrução de correção de extensão para o retry único. Puro/testável.
 * O modelo precisa saber o que entregou e qual é o piso — e não pode
 * "fechar" dizendo que atingiu a meta quando o card está curto.
 */
fun longTextExpansionInstruction(text: String, request: LongTextRequest): String {
    val current = if (request.kind == LongTextKind.DEMO) {
        "${cardTurnCount(text)} réplicas"
    } else {
        "${cardWordCount(text)} palavras"
    }
    val goal = if (request.kind == LongTextKind.DEMO) {
        "pelo menos ${request.targetTurns} réplicas (mínimo ${request.targetTurns * 8 / 10})"
    } else {
        "pelo menos ${request.targetWords} palavras (mínimo ${request.targetWords * 8 / 10})"
    }
    return "\n\n## CORREÇÃO DE EXTENSÃO (OBRIGATÓRIA)\n" +
        "A resposta anterior ficou com $current, abaixo da meta de ${request.minutes} min. " +
        "Reescreva o bloco COMPLETO dentro de <texto-do-card> com $goal. " +
        "Desenvolva cada ponto com explicação, exemplo e aplicação; não resuma, " +
        "não corte e não finalize abaixo do mínimo. Não afirme que atingiu a meta " +
        "se o texto ainda estiver curto."
}

/**
 * Segunda correção: em vez de reescrever do zero (o modelo tende a repetir o
 * mesmo tamanho), reenvia o texto anterior e pede uma expansão dirigida até o
 * piso. Puro/testável.
 */
fun longTextSecondExpansionInstruction(text: String, request: LongTextRequest): String {
    val current = if (request.kind == LongTextKind.DEMO) {
        "${cardTurnCount(text)} réplicas"
    } else {
        "${cardWordCount(text)} palavras"
    }
    val goal = if (request.kind == LongTextKind.DEMO) {
        "pelo menos ${request.targetTurns} réplicas (mínimo ${request.targetTurns * 8 / 10})"
    } else {
        "pelo menos ${request.targetWords} palavras (mínimo ${request.targetWords * 8 / 10})"
    }
    return "\n\n## EXPANSÃO DIRIGIDA (2ª CORREÇÃO)\n" +
        "O texto anterior ficou com $current; a meta de ${request.minutes} min é $goal. " +
        "Expanda o texto abaixo acrescentando desenvolvimento, ilustrações e aplicação " +
        "até atingir o mínimo. Mantenha a estrutura e o que já está bom; não repita " +
        "parágrafos e não encerre antes do mínimo.\n" +
        "--- TEXTO ANTERIOR ---\n${cardTextOf(text)}\n--- FIM DO TEXTO ANTERIOR ---"
}
