package com.bettertalker.app.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCoachTest {

    private fun metrics(
        durationSec: Int = 60,
        spokenWords: Int = 130,
        wpm: Int = 130,
        coverage: Int = 90,
        missing: List<String> = emptyList(),
        fillers: Map<String, Int> = emptyMap(),
    ) = SpeechMetrics(
        durationSec = durationSec,
        spokenWords = spokenWords,
        wpm = wpm,
        coveragePercent = coverage,
        missingTerms = missing,
        fillerCounts = fillers,
    )

    @Test
    fun ritmoRapidoPedePausa() {
        val f = buildCoachFeedback(metrics(wpm = 160), targetMinutes = 1)
        assertTrue(f.adjust.any { it.contains("acelerando") })
        assertTrue(f.pattern.contains("ritmo subiu"))
        assertTrue(f.exercise.contains("pausa curta"))
    }

    @Test
    fun ritmoLentoPedeMenosPausa() {
        val f = buildCoachFeedback(metrics(wpm = 100), targetMinutes = 1)
        assertTrue(f.adjust.any { it.contains("devagar") })
        assertTrue(f.pattern.contains("abaixo do alvo"))
        assertTrue(f.exercise.contains("sem pausas longas"))
    }

    @Test
    fun tudoOkElogiaEPropoeDesafio() {
        val f = buildCoachFeedback(metrics(), targetMinutes = 1)
        assertTrue(f.good.any { it.contains("Ritmo dentro") })
        assertTrue(f.good.any { it.contains("Boa cobertura") })
        assertTrue(f.adjust.isEmpty())
        assertTrue(f.exercise.contains("Grave 1 min"))
    }

    @Test
    fun coberturaBaixaListaTermos() {
        val f = buildCoachFeedback(
            metrics(coverage = 50, missing = listOf("caminho", "estreito")),
            targetMinutes = 1,
        )
        assertTrue(f.adjust.any { it.contains("Faltou cobrir 50%") })
        assertTrue(f.adjust.any { it.contains("caminho") })
        assertTrue(f.pattern.contains("partes do texto"))
        assertTrue(f.exercise.contains("caminho"))
    }

    @Test
    fun muletasViramIndicadorSemJulgamento() {
        val f = buildCoachFeedback(
            metrics(fillers = mapOf("né" to 3)),
            targetMinutes = 1,
        )
        assertTrue(f.adjust.any { it.contains("né") && it.contains("3") })
        assertTrue(f.pattern.contains("muletas"))
        assertTrue(f.exercise.contains("né"))
        assertTrue(f.exercise.contains("pausa"))
    }

    @Test
    fun tempoLongoECurtoAjustamMeta() {
        val long = buildCoachFeedback(metrics(durationSec = 100), targetMinutes = 1)
        assertTrue(long.adjust.any { it.contains("passou da meta") })

        val short = buildCoachFeedback(metrics(durationSec = 20), targetMinutes = 1)
        assertTrue(short.adjust.any { it.contains("abaixo da meta") })
    }

    @Test
    fun semFalaExplicaEFallback() {
        val f = buildCoachFeedback(
            metrics(spokenWords = 0, wpm = 0, coverage = 0),
            targetMinutes = 1,
        )
        assertTrue(f.adjust.any { it.contains("Não recebi fala") })
        assertTrue(f.pattern.contains("não retornou texto"))
        assertTrue(f.exercise.contains("microfone"))
    }

    @Test
    fun exercicioUsaTituloDaSegundaSecao() {
        val f = buildCoachFeedback(
            metrics(wpm = 160),
            targetMinutes = 1,
            sectionTitles = listOf("Introdução", "O caminho para a vida"),
        )
        assertTrue(f.exercise.contains("O caminho para a vida"))
    }

    @Test
    fun textoParaTtsTemOsQuatroBlocos() {
        val speech = coachFeedbackSpeech(buildCoachFeedback(metrics(), targetMinutes = 1))
        assertTrue(speech.contains("O que foi bem"))
        assertTrue(speech.contains("Padrão observado"))
        assertTrue(speech.contains("Exercício"))
    }

    @Test
    fun formatDuracaoHumana() {
        assertEquals("20 s", formatCoachDuration(20))
        assertEquals("1 min 20 s", formatCoachDuration(80))
    }
}
