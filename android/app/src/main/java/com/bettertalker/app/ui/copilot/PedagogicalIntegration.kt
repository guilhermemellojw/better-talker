package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentResolver
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge

// --- PEDAGOGY-1-MIN / P6 — fronteira pura do único seam de `send()` ---------

/** Ações locais: nenhuma autoriza provedor, gate, aceite, avanço ou inserção. */
internal sealed interface PedagogicalAction {
    data class Reformulate(val topic: String, val simplify: Boolean) : PedagogicalAction
    data class Develop(val topic: String) : PedagogicalAction

    /** Reconhecimento sem efeito: nenhum estado é mudado. */
    data object PositiveFeedback : PedagogicalAction
    data class Clarify(val reason: String) : PedagogicalAction
    data object Delegate : PedagogicalAction
}

/** Tema vem SEMPRE do referente certificado, nunca do foco corrente. */
internal fun pedagogicalTopic(
    referent: ConversationalReferentResolver.Referent,
    fallbackSectionId: String?,
    sectionTitles: Map<String, String>,
): String = when (referent) {
    is ConversationalReferentResolver.Referent.DeepenCard ->
        referent.card.targetLabel?.takeIf { it.isNotBlank() }
            ?: referent.card.targetId?.takeIf { it.isNotBlank() }
            ?: referent.card.summary.takeIf { it.isNotBlank() }
            ?: fallbackSectionId?.let { sectionTitles[it] ?: it }
            ?: ""
    is ConversationalReferentResolver.Referent.ExplainSuggestion -> referent.suggestion.label
    is ConversationalReferentResolver.Referent.ExplainActiveSection ->
        sectionTitles[referent.sectionId] ?: referent.sectionId
    else -> fallbackSectionId?.let { sectionTitles[it] ?: it } ?: ""
}

/** Tradução tipada da ponte certificada para ações que o ViewModel executa. */
internal fun planPedagogicalAction(
    dispatch: PedagogicalContinuityBridge.Dispatch,
    sectionTitles: Map<String, String>,
): PedagogicalAction = when (dispatch) {
    is PedagogicalContinuityBridge.Dispatch.Reformulate -> {
        val topic = pedagogicalTopic(dispatch.referent, dispatch.sectionId, sectionTitles)
        if (topic.isBlank()) PedagogicalAction.Clarify("referente sem tema identificável")
        else PedagogicalAction.Reformulate(topic, dispatch.simplify)
    }
    is PedagogicalContinuityBridge.Dispatch.Develop -> {
        val topic = pedagogicalTopic(dispatch.referent, dispatch.sectionId, sectionTitles)
        if (topic.isBlank()) PedagogicalAction.Clarify("referente sem tema identificável")
        else PedagogicalAction.Develop(topic)
    }
    PedagogicalContinuityBridge.Dispatch.PositiveFeedback -> PedagogicalAction.PositiveFeedback
    is PedagogicalContinuityBridge.Dispatch.Clarify -> PedagogicalAction.Clarify(dispatch.reason)
    PedagogicalContinuityBridge.Dispatch.Delegate -> PedagogicalAction.Delegate
}

// --- P7 — preservação literal por SEGMENTOS, sem tokens de stash ------------
// Rótulos de proveniência TRUST, aspas (retas/tipográficas) e referências são
// identificados uma única vez e copiados byte a byte. Nenhum token interno é
// inserido no texto: o mecanismo de aninhamento/NUL foi eliminado.
// HOTFIX-68/P0.1 — `(?i)` é a única flag embutida comum a JVM e ICU
// (documentação ICU: ismwx). `u`/`U` continuam proibidos: são flags Java
// que quebram o `<clinit>` no Android. Letras acentuadas são explícitas
// ([Éé], [Ãã], [Çç], [Óó]) porque o case folding ASCII da JVM não as cobre.
// Fronteiras portáteis: `[\p{L}\p{N}_]` (revisadas do patch de D).
private val PEDAGOGICAL_PROTECTED = Regex(
    """(?i)[Tt]recho [Rr]ecuperado[^\n]*|“[^”\n]*”|"[^"\n]*"|«[^»\n]*»|(?<![\p{L}\p{N}_])[A-Za-zÀ-ÿ]+\s+\d{1,3}(?::\d{1,3})?(?![\p{L}\p{N}_])|(?<![\p{L}\p{N}_])\d{1,3}:\d{1,3}(?![\p{L}\p{N}_])""",
)

// F7 — marcadores com peso discursivo só no início do texto ou da frase.
// O prefixo capturado impede a remoção depois de negação/condição/
// qualificador e preserva a pontuação; `(?![\p{L}\p{N}_])` substitui `\b`.
private val PEDAGOGICAL_SENTENCE_FILLERS = listOf(
    Regex(
        """(?i)(^|[.!?\n]\s+)([Éé] importante ([Rr]essaltar|[Nn]otar|[Ss]alientar|[Dd]estacar) que|[Ii]mporta ([Rr]essaltar|[Nn]otar|[Ss]alientar) que|[Vv]ale ([Rr]essaltar|[Nn]otar|[Ll]embrar) que|[Cc]abe destacar que)(?![\p{L}\p{N}_])[,\s]*""",
    ) to "$1",
)

// P9 — CONGELAMENTO DO BETA: apenas marcadores de REFORMULAÇÃO inequívoca
// são removíveis. `basicamente` foi RETIRADO: exprime grau/abrangência e
// preservar o sentido tem precedência sobre reduzir texto. Nenhuma nova
// regex ou guarda contextual foi adicionada.
//
// Também permanecem preservados: em geral, de certa forma, literalmente,
// simplesmente, na verdade, por assim dizer, em suma, em resumo, além de
// todo conectivo lógico (se, porque, portanto, mas, exceto, não, talvez).
private val PEDAGOGICAL_SAFE_FILLERS = listOf(
    Regex(
        """(?i)(?<![\p{L}\p{N}_])([Oo]u seja|[Ee]m outras palavras|[Dd]ito de outra forma|[Ee]m outros termos)(?![\p{L}\p{N}_])[,\s]*""",
    ) to "",
)

private val PEDAGOGICAL_PHRASES = listOf(
    Regex("""(?i)(?<![\p{L}\p{N}_])[Cc]om o objetivo de(?![\p{L}\p{N}_])""") to "para",
    Regex("""(?i)(?<![\p{L}\p{N}_])[Aa] fim de(?![\p{L}\p{N}_])""") to "para",
    Regex("""(?i)(?<![\p{L}\p{N}_])[Nn]o que diz respeito a(?![\p{L}\p{N}_])""") to "sobre",
    Regex("""(?i)(?<![\p{L}\p{N}_])[Ll]evando em considera[Çç][Ãã]o(?![\p{L}\p{N}_])""") to "considerando",
)

private val PEDAGOGICAL_RISK_PREFIX = Regex(
    """(?i)(?<![\p{L}\p{N}_])([Nn][Ãã]o|[Nn]unca|[Nn]em|[Ss]e|[Cc]aso|[Aa]penas|[Ss][Óó]|[Ss]omente|[Tt]alvez)\s*$""",
)

private fun simplifySegment(segment: String): String {
    var out = segment
    PEDAGOGICAL_SENTENCE_FILLERS.forEach { (pattern, replacement) ->
        out = pattern.replace(out, replacement)
    }
    fun guardedReplace(pattern: Regex, replacement: String) {
        out = pattern.replace(out) { match ->
            val before = out.take(match.range.first).takeLast(40)
            if (PEDAGOGICAL_RISK_PREFIX.containsMatchIn(before)) match.value else replacement
        }
    }
    PEDAGOGICAL_SAFE_FILLERS.forEach { (pattern, replacement) ->
        guardedReplace(pattern, replacement)
    }
    PEDAGOGICAL_PHRASES.forEach { (pattern, replacement) ->
        guardedReplace(pattern, replacement)
    }
    return out.replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex(""" *\n *"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
}

/**
 * Simplificação efetiva da linguagem, sem inventar conteúdo: remove apenas
 * marcadores discursivos e expressões verbosas dos segmentos NÃO protegidos.
 * Aspas, rótulos TRUST #60 e referências são copiados intactos; se a
 * transformação não puder ser comprovada, o texto original é devolvido.
 */
internal fun simplifyPedagogicalText(text: String): String {
    if (text.isBlank()) return text
    val protectedBefore = PEDAGOGICAL_PROTECTED.findAll(text).map { it.value }.toList()
    val out = StringBuilder(text.length)
    var cursor = 0
    PEDAGOGICAL_PROTECTED.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        if (start > cursor) out.append(simplifySegment(text.substring(cursor, start)))
        out.append(match.value) // byte a byte, sem token e sem reprocessamento
        cursor = end
    }
    if (cursor < text.length) out.append(simplifySegment(text.substring(cursor)))
    val result = out.toString()
    // Fail-safe: qualquer token residual, perda ou reordenação de segmento
    // protegido invalida a transformação.
    if (result.contains('\u0000')) return text
    if (PEDAGOGICAL_PROTECTED.findAll(result).map { it.value }.toList() != protectedBefore) return text
    return result
}
