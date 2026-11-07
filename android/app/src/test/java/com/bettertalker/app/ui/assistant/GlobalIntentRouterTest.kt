package com.bettertalker.app.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roteador local do assistente global — puro, sem LLM/Room. */
class GlobalIntentRouterTest {

    private val esperanca = GlobalNote("n1", "Esperança viva")
    private val cultivando = GlobalNote("n2", "Cultivando o interesse")
    private val smoke = GlobalNote("n3", "Smoke S34")
    private val smokeDemo = GlobalNote("n4", "Smoke S34 demo")
    private val notes = listOf(esperanca, cultivando, smoke, smokeDemo)

    @Test
    fun abrirUnico_casaPorTitulo() {
        val r = GlobalIntentRouter.route("abrir o esboço esperança", notes)
        assertTrue(r is GlobalRoute.OpenNote)
        assertEquals("n1", (r as GlobalRoute.OpenNote).note.id)
    }

    @Test
    fun abrirAmbiguo_listaCandidatos() {
        val r = GlobalIntentRouter.route("abrir o esboço smoke", notes)
        assertTrue(r is GlobalRoute.AskWhichNote)
        val ask = r as GlobalRoute.AskWhichNote
        assertEquals(GlobalNoteAction.OPEN, ask.action)
        assertEquals(setOf("n3", "n4"), ask.candidates.map { it.id }.toSet())
    }

    @Test
    fun abrirInexistente_naoInventa() {
        val r = GlobalIntentRouter.route("abrir o esboço zzz", notes)
        assertTrue(r is GlobalRoute.NotFound)
        assertEquals(notes.take(6), (r as GlobalRoute.NotFound).recent)
    }

    @Test
    fun listarEsbocos() {
        assertEquals(GlobalRoute.ListNotes, GlobalIntentRouter.route("quais esboços tenho?", notes))
    }

    @Test
    fun criarEsboco() {
        assertEquals(
            GlobalRoute.CreateNote,
            GlobalIntentRouter.route("criar um esboço novo", notes),
        )
    }

    @Test
    fun treinarComNotaVinculada() {
        val r = GlobalIntentRouter.route("iniciar treino", notes, boundNoteId = "n1")
        assertTrue(r is GlobalRoute.StartTrain)
        assertEquals("n1", (r as GlobalRoute.StartTrain).note.id)
    }

    @Test
    fun palcoComNotaVinculada() {
        val r = GlobalIntentRouter.route("ir para o palco", notes, boundNoteId = "n2")
        assertTrue(r is GlobalRoute.OpenPresent)
        assertEquals("n2", (r as GlobalRoute.OpenPresent).note.id)
    }

    @Test
    fun treinarSemNota_perguntaQual() {
        val r = GlobalIntentRouter.route("iniciar treino", notes)
        assertTrue(r is GlobalRoute.AskNoteForAction)
        assertEquals(
            GlobalNoteAction.TRAIN,
            (r as GlobalRoute.AskNoteForAction).action,
        )
    }

    @Test
    fun treinarComPontoEDiscurso_abreNota() {
        // Fluxo Alexa: “Quero treinar o Ponto 1 do discurso sobre esperança.”
        val r = GlobalIntentRouter.route(
            "Quero treinar o Ponto 1 do discurso sobre esperança",
            notes,
        )
        assertTrue(r is GlobalRoute.OpenNote)
        assertEquals("n1", (r as GlobalRoute.OpenNote).note.id)
    }

    @Test
    fun treinarAmbiguo_perguntaQualComAcao() {
        val r = GlobalIntentRouter.route("iniciar treino smoke", notes)
        assertTrue(r is GlobalRoute.AskWhichNote)
        assertEquals(
            GlobalNoteAction.TRAIN,
            (r as GlobalRoute.AskWhichNote).action,
        )
    }

    @Test
    fun ajudaEConfigurarIa() {
        assertEquals(GlobalRoute.Help, GlobalIntentRouter.route("ajuda", notes))
        assertEquals(GlobalRoute.ConfigureAi, GlobalIntentRouter.route("configurar IA", notes))
    }

    @Test
    fun simNoPendente_confirma() {
        val pending = GlobalPending.OpenNote(esperanca)
        assertEquals(
            GlobalRoute.Confirm(pending),
            GlobalIntentRouter.route("sim", notes, pending = pending),
        )
        assertEquals(
            GlobalRoute.Cancel,
            GlobalIntentRouter.route("não", notes, pending = pending),
        )
    }

    @Test
    fun escolhaPendente_porNumeroETitulo() {
        val pending = GlobalPending.ChooseNote(
            candidates = listOf(smoke, smokeDemo),
            action = GlobalNoteAction.OPEN,
        )
        val byNumber = GlobalIntentRouter.route("1", notes, pending = pending)
        assertTrue(byNumber is GlobalRoute.OpenNote)
        assertEquals("n3", (byNumber as GlobalRoute.OpenNote).note.id)

        val byTitle = GlobalIntentRouter.route("demo", notes, pending = pending)
        assertTrue(byTitle is GlobalRoute.OpenNote)
        assertEquals("n4", (byTitle as GlobalRoute.OpenNote).note.id)
    }

    @Test
    fun perguntaGeral_vaiParaChat() {
        val r = GlobalIntentRouter.route("O que é uma boa introdução?", notes)
        assertTrue(r is GlobalRoute.Chat)
    }
}
