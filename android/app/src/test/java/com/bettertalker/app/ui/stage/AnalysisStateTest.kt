package com.bettertalker.app.ui.stage

import com.bettertalker.app.data.voice.SpeechTranscriptAccumulator
import com.bettertalker.app.domain.coach.CoachFeedback
import com.bettertalker.app.domain.coach.SpeechMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisStateTest {

    private fun metrics() = SpeechMetrics(
        durationSec = 60,
        spokenWords = 130,
        wpm = 130,
        coveragePercent = 90,
    )

    private fun feedback() = CoachFeedback(
        good = listOf("Ritmo bom."),
        adjust = emptyList(),
        pattern = "Consistente.",
        exercise = "Grave 1 min.",
    )

    @Test
    fun rotulosDosEstados() {
        assertNull(analysisStateLabel(AnalysisState.Idle))
        assertEquals("Ouvindo…", analysisStateLabel(AnalysisState.Listening))
        assertEquals("Analisando…", analysisStateLabel(AnalysisState.Processing))
        assertEquals(
            "Resultado pronto",
            analysisStateLabel(AnalysisState.Result(metrics(), feedback())),
        )
        assertEquals(
            "Análise indisponível",
            analysisStateLabel(AnalysisState.Error("sem microfone")),
        )
    }

    @Test
    fun resultadoCarregaMetricasFeedbackEAvisoOpcional() {
        val result = AnalysisState.Result(metrics(), feedback(), warning = "STT parcial")
        assertEquals(130, result.metrics.wpm)
        assertEquals("Consistente.", result.feedback.pattern)
        assertEquals("STT parcial", result.warning)
    }

    @Test
    fun acumuladorConcatenaSegmentosEDeduplica() {
        val acc = SpeechTranscriptAccumulator()
        assertEquals("", acc.transcript())
        acc.addFinal("Primeiro segmento.")
        acc.addFinal("Segundo segmento.")
        acc.addFinal("Segundo segmento.")
        assertEquals("Primeiro segmento. Segundo segmento.", acc.transcript())
        acc.clear()
        assertEquals("", acc.transcript())
    }

    @Test
    fun acumuladorIgnoraVazioEPontuacao() {
        val acc = SpeechTranscriptAccumulator()
        acc.addFinal("  ")
        acc.addFinal("— ...")
        assertEquals("", acc.transcript())
        acc.addFinal("")
        assertEquals("", acc.transcript())
    }
}
