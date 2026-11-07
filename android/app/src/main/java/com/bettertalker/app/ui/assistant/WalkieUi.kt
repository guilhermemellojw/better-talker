package com.bettertalker.app.ui.assistant

import com.bettertalker.app.data.copilot.NextStepSuggestion
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.voice.PttState

/**
 * v4.0 — modo Walkie do Assistente.
 *
 * O walkie é a apresentação padrão quando há nota: PTT grande no centro,
 * topo mínimo e a última resposta em texto. O chat completo fica a um toque.
 * Puro/testável: sem Compose, Room ou rede.
 */
enum class AssistantPresentation { WALKIE, FULL }

/** Walkie só no Assistente embutido e com nota ativa. */
fun startsInWalkie(embedded: Boolean, hasNote: Boolean): Boolean =
    embedded && hasNote

/** Rótulo do estado central do walkie. */
fun walkieStatusLabel(
    pttState: PttState,
    turnPhase: TurnPhase?,
    ttsSpeaking: Boolean,
): String = when {
    pttState == PttState.Listening || pttState == PttState.Starting -> "Ouvindo…"
    pttState == PttState.Transcribing -> "Transcrevendo…"
    pttState == PttState.Thinking -> "Pensando…"
    pttState == PttState.Speaking || ttsSpeaking -> "Falando…"
    turnPhase == TurnPhase.WAITING ||
        turnPhase == TurnPhase.REASONING ||
        turnPhase == TurnPhase.STREAMING -> "Pensando…"
    else -> "Segure para falar"
}

/** Tamanho do PTT: 160dp no phone, 200dp no tablet. Puro/testável. */
fun walkiePttSizeDp(widthDp: Float): Float =
    if (widthDp >= 600f) 200f else 160f

/** Linha discreta de prática no topo do walkie. */
fun walkiePracticeLine(snapshot: PracticeSnapshot?): String? {
    if (snapshot == null) return null
    val training = if (snapshot.hasTrainingText) "Treino pronto" else "Treino sem texto"
    val resume = snapshot.resumeLabel?.let { " · $it" } ?: ""
    return "Prática · $training · Palco: ${snapshot.presentLabel}$resume"
}

/** Pílula discreta de próximo passo; a ação fica no chat completo. */
fun walkieNextStepLabel(suggestion: NextStepSuggestion?): String? =
    suggestion?.let { "Próximo: ${it.label}" }

/**
 * Prévia curta da última resposta: remove tags internas/markdown, colapsa
 * espaços e corta com reticências. Puro/testável.
 */
fun walkiePreview(text: String, maxChars: Int = 240): String {
    var t = text
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("〈[^〉]*〉"), " ")
        .replace(Regex("[#*_`>]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (t.length > maxChars) t = t.take(maxChars).trimEnd() + "…"
    return t
}
