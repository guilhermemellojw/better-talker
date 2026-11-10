package com.bettertalker.app.ui.assistant

import com.bettertalker.app.data.copilot.PracticePresentState
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.data.voice.PttState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.0/v5.1 Walkie — decisões puras de apresentação/estado/menu. */
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
    fun menuDoTituloMudaComOContexto() {
        assertEquals(
            listOf(
                AssistantMenuAction.MY_OUTLINES,
                AssistantMenuAction.LIBRARY,
                AssistantMenuAction.PREPARE,
                AssistantMenuAction.TRAIN,
                AssistantMenuAction.PRESENT,
                AssistantMenuAction.CREATE_NOTE,
            ),
            assistantMenu(hasNote = false),
        )
        assertEquals(
            listOf(
                AssistantMenuAction.OPEN_NOTE,
                AssistantMenuAction.MY_OUTLINES,
                AssistantMenuAction.LIBRARY,
                AssistantMenuAction.PREPARE,
                AssistantMenuAction.TRAIN,
                AssistantMenuAction.PRESENT,
                AssistantMenuAction.CREATE_NOTE,
                AssistantMenuAction.GENERAL_CHAT,
            ),
            assistantMenu(hasNote = true),
        )
        assertEquals("Meus esboços", assistantMenuLabel(AssistantMenuAction.MY_OUTLINES))
        assertEquals("Criar novo esboço", assistantMenuLabel(AssistantMenuAction.CREATE_NOTE))
        assertEquals("Abrir nota", assistantMenuLabel(AssistantMenuAction.OPEN_NOTE))
        assertEquals("Meus esboços", assistantMenuLabel(AssistantMenuAction.CHANGE_NOTE))
        assertEquals("Biblioteca", assistantMenuLabel(AssistantMenuAction.LIBRARY))
        assertEquals("Preparo", assistantMenuLabel(AssistantMenuAction.PREPARE))
        assertEquals("Treino", assistantMenuLabel(AssistantMenuAction.TRAIN))
        assertEquals("Palco", assistantMenuLabel(AssistantMenuAction.PRESENT))
        assertEquals(
            "Conversa geral (sem nota)",
            assistantMenuLabel(AssistantMenuAction.GENERAL_CHAT),
        )
    }

    @Test
    fun tituloUsaNotaQuandoExiste() {
        assertEquals("Assistente", assistantTitle(hasNote = false, noteTitle = "Zedeque"))
        assertEquals("Assistente", assistantTitle(hasNote = true, noteTitle = " "))
        assertEquals("Zedeque", assistantTitle(hasNote = true, noteTitle = "Zedeque"))
    }

    @Test
    fun chipDeChaveSoQuandoARespostaBloqueia() {
        assertFalse(assistantNeedsKey(null))
        assertFalse(assistantNeedsKey("Tudo certo."))
        assertTrue(
            assistantNeedsKey(
                "Configure a chave de IA na tela Modelo IA para conversar sobre o esboço.",
            )
        )
        assertTrue(
            assistantNeedsKey(
                "Para compor texto, configure a chave do DeepSeek na tela Modelo IA.",
            )
        )
    }
}
