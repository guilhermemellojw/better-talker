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

// Citações literais, rótulos de trecho recuperado e referências são intocáveis:
// simplificar a linguagem nunca pode reescrever evidência nem criar afirmação.
private val PEDAGOGICAL_QUOTE = Regex("""[“"»][^”"«\n]*[”"«]""")
private val PEDAGOGICAL_REFERENCE = Regex(
    """Trecho recuperado[^\n]*|\b\d?\s*[A-Za-zÀ-ÿ]+\s+\d{1,3}(?::\d{1,3})?\b|\b\d{1,3}:\d{1,3}\b""",
)
private val PEDAGOGICAL_FILLERS = listOf(
    Regex("""(?iu)\b(é importante (ressaltar|notar|salientar|destacar) que|importa (ressaltar|notar|salientar) que|vale (ressaltar|notar|lembrar) que|cabe destacar que)\b[,\s]*""") to "",
    Regex("""(?iu)\b(ou seja|em outras palavras|dito de outra forma|em outros termos)\b[,\s]*""") to "",
    Regex("""(?iu)\b(basicamente|simplesmente|na verdade|de certa forma|por assim dizer|literalmente|em suma|em resumo|em geral)\b[,\s]*""") to "",
    Regex("""(?iu)\bcom o objetivo de\b""") to "para",
    Regex("""(?iu)\ba fim de\b""") to "para",
    Regex("""(?iu)\bno que diz respeito a\b""") to "sobre",
    Regex("""(?iu)\blevando em consideração\b""") to "considerando",
)

/**
 * Simplificação efetiva da linguagem, sem inventar conteúdo: remove apenas
 * marcadores discursivos e expressões verbosas; aspas, rótulos TRUST #60 e
 * referências bíblicas voltam byte a byte.
 */
internal fun simplifyPedagogicalText(text: String): String {
    if (text.isBlank()) return text
    val protected = mutableListOf<String>()
    fun stash(value: String): String {
        val token = "\u0000${protected.size}\u0000"
        protected += value
        return token
    }
    var staged = PEDAGOGICAL_QUOTE.replace(text) { stash(it.value) }
    staged = PEDAGOGICAL_REFERENCE.replace(staged) { stash(it.value) }
    var out = staged
    PEDAGOGICAL_FILLERS.forEach { (pattern, replacement) ->
        out = pattern.replace(out, replacement)
    }
    out = out.replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex(""" *\n *"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    return protected.foldIndexed(out) { i, acc, value -> acc.replace("\u0000$i\u0000", value) }
}
