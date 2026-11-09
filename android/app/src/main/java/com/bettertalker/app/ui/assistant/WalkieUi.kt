package com.bettertalker.app.ui.assistant

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

/** Ações do menu do título (seletor de contexto, nunca um ⋮). */
enum class AssistantMenuAction {
    OPEN_NOTE, CHANGE_NOTE, GENERAL_CHAT, PICK_NOTE, CREATE_NOTE
}

/** Home global escolhe esboço/cria; walkie abre/troca/desvincula a nota. */
fun assistantMenu(hasNote: Boolean): List<AssistantMenuAction> = if (hasNote) {
    listOf(
        AssistantMenuAction.OPEN_NOTE,
        AssistantMenuAction.CHANGE_NOTE,
        AssistantMenuAction.GENERAL_CHAT,
    )
} else {
    listOf(AssistantMenuAction.PICK_NOTE, AssistantMenuAction.CREATE_NOTE)
}

fun assistantMenuLabel(action: AssistantMenuAction): String = when (action) {
    AssistantMenuAction.OPEN_NOTE -> "Abrir nota"
    AssistantMenuAction.CHANGE_NOTE -> "Trocar nota"
    AssistantMenuAction.GENERAL_CHAT -> "Conversa geral (sem nota)"
    AssistantMenuAction.PICK_NOTE -> "Escolher esboço"
    AssistantMenuAction.CREATE_NOTE -> "Criar novo esboço"
}

/** Título da top bar: nome da nota no walkie; "Assistente" na home global. */
fun assistantTitle(hasNote: Boolean, noteTitle: String): String =
    if (hasNote) noteTitle.ifBlank { "Assistente" } else "Assistente"

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

/** Chip "Configurar IA" só quando a última resposta bloqueia por falta de chave. */
fun assistantNeedsKey(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    return text.contains("Configure a chave de IA") ||
        text.contains("configure a chave do DeepSeek")
}
