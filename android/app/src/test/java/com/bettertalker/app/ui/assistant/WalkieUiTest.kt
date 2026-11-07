package com.bettertalker.app.ui.assistant

import com.bettertalker.app.data.copilot.NextStepSuggestion
import com.bettertalker.app.data.copilot.PracticePresentState
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.copilot.ResolvedChatNav
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.voice.PttState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.0 Walkie — decisões puras de apresentação/estado/preview. */
class WalkieUiTest {

    @Test
    fun walkieSoNoAssistenteComNota() {
        assertTrue(startsInWalkie(embedded = true, hasNote = true))
        assertFalse(startsInWalkie(embedded = true, hasNote = false))
        assertFalse(startsInWalkie(embedded = false, hasNote = true))
    }

    @Test
    fun estadosDoWalkie() {
        assertEquals("Ouvindo…", walkieStatusLabel(PttState.Listening, null, false))
        assertEquals("Ouvindo…", walkieStatusLabel(PttState.Starting, null, false))
        assertEquals("Transcrevendo…", walkieStatusLabel(PttState.Transcribing, null, false))
        assertEquals("Pensando…", walkieStatusLabel(PttState.Thinking, null, false))
        assertEquals("Falando…", walkieStatusLabel(PttState.Speaking, null, false))
        assertEquals("Falando…", walkieStatusLabel(PttState.Idle, null, ttsSpeaking = true))
        assertEquals("Pensando…", walkieStatusLabel(PttState.Idle, TurnPhase.STREAMING, false))
        assertEquals("Segure para falar", walkieStatusLabel(PttState.Idle, null, false))
    }

    @Test
    fun pttCresceNoTablet() {
        assertEquals(160f, walkiePttSizeDp(360f))
        assertEquals(160f, walkiePttSizeDp(599f))
        assertEquals(200f, walkiePttSizeDp(600f))
        assertEquals(200f, walkiePttSizeDp(900f))
    }

    @Test
    fun previewLimpaTagsMarkdownECorta() {
        val text = "<texto-do-card>**Olá** 〈sugestão〉mundo〈/sugestão〉 " +
            "com   espaços</texto-do-card>"
        assertEquals("Olá mundo com espaços", walkiePreview(text))
        val long = "a".repeat(300)
        val cut = walkiePreview(long, maxChars = 10)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= 11)
    }

    @Test
    fun linhaDePraticaResumeTreinoEPalco() {
        val snapshot = PracticeSnapshot(
            noteId = "note",
            hasTrainingText = true,
            presentState = PracticePresentState.READY,
            presentLabel = "Tribuna pronta",
            savedSectionIndex = 1,
            savedSectionTitle = "Ponto 1",
            savedElapsedSec = 65,
        )
        val line = walkiePracticeLine(snapshot)!!
        assertTrue(line, line.contains("Treino pronto"))
        assertTrue(line, line.contains("Palco: Tribuna pronta"))
        assertTrue(line, line.contains("continuar da seção 2"))
        assertNull(walkiePracticeLine(null))
    }

    @Test
    fun pilulaDeProximoPasso() {
        val s = NextStepSuggestion(
            label = "Iniciar Treino",
            detail = "Texto completo.",
            action = ResolvedChatNav.StartTrain,
        )
        assertEquals("Próximo: Iniciar Treino", walkieNextStepLabel(s))
        assertNull(walkieNextStepLabel(null))
    }
}
