package com.bettertalker.app.domain.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMetricsTest {

    @Test
    fun calculaPalavrasRitmoECobertura() {
        val transcript = "Jeová Deus oferece vida eterna. O caminho é estreito."
        val target = "Jeová Deus oferece vida eterna. O caminho é estreito."
        val metrics = computeSpeechMetrics(transcript, target, speechDurationSec = 60)

        assertEquals(9, metrics.spokenWords)
        assertEquals(9, metrics.wpm)
        assertEquals(100, metrics.coveragePercent)
        assertEquals(emptyList<String>(), metrics.missingTerms)
        assertEquals(130, metrics.targetWpm)
    }

    @Test
    fun coberturaParcialListaTermosAusentes() {
        val metrics = computeSpeechMetrics(
            transcript = "Jeová oferece vida eterna",
            targetText = "Jeová Deus oferece vida eterna. O caminho é estreito.",
            speechDurationSec = 30,
        )
        // 4 de 7 palavras de conteúdo (deus, caminho, estreito faltaram).
        assertEquals(57, metrics.coveragePercent)
        assertTrue(metrics.missingTerms.contains("Deus"))
        assertTrue(metrics.missingTerms.contains("caminho"))
        assertTrue(metrics.missingTerms.contains("estreito"))
    }

    @Test
    fun textoAlvoVazioNaoPenaliza() {
        val metrics = computeSpeechMetrics("qualquer fala", "", speechDurationSec = 10)
        assertEquals(100, metrics.coveragePercent)
        assertTrue(metrics.missingTerms.isEmpty())
    }

    @Test
    fun acentosEPontuacaoNaoQuebramCobertura() {
        val metrics = computeSpeechMetrics(
            transcript = "jerusalem e o caminho",
            targetText = "Jerusalém e o caminho.",
            speechDurationSec = 20,
        )
        assertEquals(100, metrics.coveragePercent)
    }

    @Test
    fun contaMuletasComRotuloLegivel() {
        val metrics = computeSpeechMetrics(
            transcript = "Então, tipo, né? Então, hum...",
            targetText = "Um texto qualquer sobre o tema.",
            speechDurationSec = 20,
        )
        assertEquals(2, metrics.fillerCounts["então"])
        assertEquals(1, metrics.fillerCounts["tipo"])
        assertEquals(1, metrics.fillerCounts["né"])
        assertEquals(1, metrics.fillerCounts["hum"])
        assertEquals(5, metrics.fillerTotal)
    }

    @Test
    fun duracaoZeroNaoDividePorZero() {
        val metrics = computeSpeechMetrics("uma fala", "uma fala", speechDurationSec = 0)
        assertEquals(0, metrics.wpm)
        assertEquals(2, metrics.spokenWords)
    }

    @Test
    fun missingTermsLimitaACinco() {
        val metrics = computeSpeechMetrics(
            transcript = "",
            targetText = "alfa beta gama delta epsilon zeta eta theta",
            speechDurationSec = 30,
        )
        assertEquals(5, metrics.missingTerms.size)
    }

    @Test
    fun wpmUsaTempoDeFalaNaoTempoTotalComPausas() {
        // 65 palavras em 30 s de fala = 130 wpm, mesmo com sessão de 90 s.
        val transcript = (1..65).joinToString(" ") { "palavra$it" }
        val metrics = computeSpeechMetrics(
            transcript = transcript,
            targetText = transcript,
            speechDurationSec = 30,
            sessionDurationSec = 90,
        )
        assertEquals(130, metrics.wpm)
        assertEquals(30, metrics.speechDurationSec)
        assertEquals(90, metrics.sessionDurationSec)
    }

    @Test
    fun contadorIgnoraPontuacaoSolta() {
        assertEquals(2, countSpokenWords("olá, — mundo!"))
        assertEquals(0, countSpokenWords("— ... !"))
    }
}
