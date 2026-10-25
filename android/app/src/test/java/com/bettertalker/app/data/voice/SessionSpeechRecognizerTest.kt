package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O acumulador é a parte pura do SessionSpeechRecognizer: pausar/retomar não
 * pode perder o texto; só o descarte explícito limpa.
 */
class SessionSpeechRecognizerTest {

    @Test
    fun pausaERetomadaPreservamTranscricao() {
        val acc = SpeechTranscriptAccumulator()
        acc.addFinal("Primeiro trecho.")
        // "pausa" no app não toca no acumulador; retomar adiciona o próximo.
        acc.addFinal("Segundo trecho.")
        assertEquals("Primeiro trecho. Segundo trecho.", acc.transcript())
    }

    @Test
    fun descarteExplicitoLimpaTranscricao() {
        val acc = SpeechTranscriptAccumulator()
        acc.addFinal("Texto que será descartado.")
        acc.clear()
        assertEquals("", acc.transcript())
    }

    @Test
    fun silencioNaoAdicionaTexto() {
        val acc = SpeechTranscriptAccumulator()
        acc.addFinal("")
        acc.addFinal("— ...")
        assertEquals("", acc.transcript())
    }
}
