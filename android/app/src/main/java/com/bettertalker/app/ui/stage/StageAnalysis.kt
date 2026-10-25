package com.bettertalker.app.ui.stage

import com.bettertalker.app.domain.coach.CoachFeedback
import com.bettertalker.app.domain.coach.SpeechMetrics

/**
 * Estado do Ensaio com análise (T3-A).
 *
 * `Interrupted` NÃO gera resultado: preserva a transcrição e oferece
 * Retomar/Parar. `Processing`/`Result` só existem depois de um Parar explícito.
 */
sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Listening : AnalysisState
    data object Paused : AnalysisState
    data object Processing : AnalysisState

    data class Interrupted(val message: String) : AnalysisState

    data class Result(
        val metrics: SpeechMetrics,
        val feedback: CoachFeedback,
        /** Aviso quando o STT falhou no meio (feedback parcial). */
        val warning: String? = null,
    ) : AnalysisState
}

/** Rótulo curto do indicador de análise. Puro/testável. */
fun analysisStateLabel(state: AnalysisState): String? = when (state) {
    AnalysisState.Idle -> null
    AnalysisState.Listening -> "Ouvindo…"
    AnalysisState.Paused -> "Pausado"
    AnalysisState.Processing -> "Analisando…"
    is AnalysisState.Interrupted -> "Serviço de voz interrompeu"
    is AnalysisState.Result -> "Resultado pronto"
}

/** A análise está em andamento (escuta/pausa/interrupção)? Puro/testável. */
fun isAnalysisActive(state: AnalysisState): Boolean =
    state is AnalysisState.Listening ||
        state is AnalysisState.Paused ||
        state is AnalysisState.Interrupted
