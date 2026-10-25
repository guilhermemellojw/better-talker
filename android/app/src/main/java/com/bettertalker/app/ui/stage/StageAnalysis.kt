package com.bettertalker.app.ui.stage

import com.bettertalker.app.domain.coach.CoachFeedback
import com.bettertalker.app.domain.coach.SpeechMetrics

/** Estado do Ensaio com análise (T3-A). */
sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Listening : AnalysisState
    data object Processing : AnalysisState

    data class Result(
        val metrics: SpeechMetrics,
        val feedback: CoachFeedback,
        /** Aviso quando o STT falhou no meio (feedback parcial). */
        val warning: String? = null,
    ) : AnalysisState

    data class Error(val message: String) : AnalysisState
}

/** Rótulo curto do indicador de análise. Puro/testável. */
fun analysisStateLabel(state: AnalysisState): String? = when (state) {
    AnalysisState.Idle -> null
    AnalysisState.Listening -> "Ouvindo…"
    AnalysisState.Processing -> "Analisando…"
    is AnalysisState.Result -> "Resultado pronto"
    is AnalysisState.Error -> "Análise indisponível"
}
