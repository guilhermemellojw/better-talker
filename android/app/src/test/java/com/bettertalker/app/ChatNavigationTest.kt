package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatNavAction
import com.bettertalker.app.data.copilot.ChatRouter
import com.bettertalker.app.data.copilot.ChatRouter.Route
import com.bettertalker.app.data.copilot.PracticePresentState
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.copilot.ResolvedChatNav
import com.bettertalker.app.data.copilot.nextStepSuggestion
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.ui.copilot.nextStepActionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fatia 4 — navegação/mentoria. Puro: sem LLM, rede, banco ou Compose.
 */
class ChatNavigationTest {

    // ---------- comandos de navegação ----------

    @Test
    fun abrirPontoPorNumero() {
        assertEquals(
            ChatNavAction.Section(number = 2, byTopic = false),
            ChatRouter.navigationCommand("Abrir o Ponto 2."),
        )
    }

    @Test
    fun abrirTopicoContaTodasAsSecoes() {
        assertEquals(
            ChatNavAction.Section(number = 2, byTopic = true),
            ChatRouter.navigationCommand("mostrar o tópico 2"),
        )
    }

    @Test
    fun abrirIntroducaoEConclusao() {
        assertEquals(
            ChatNavAction.Section(role = SectionRole.INTRO),
            ChatRouter.navigationCommand("ir para a introdução"),
        )
        assertEquals(
            ChatNavAction.Section(role = SectionRole.CONCLUSION),
            ChatRouter.navigationCommand("abrir a conclusão"),
        )
    }

    @Test
    fun mostrarFontes() {
        for (t in listOf("mostrar fontes", "ver as fontes", "fontes e apoio")) {
            assertEquals(t, ChatNavAction.Sources, ChatRouter.navigationCommand(t))
        }
    }

    @Test
    fun iniciarTreinoEIrParaOPalco() {
        assertEquals(ChatNavAction.Train, ChatRouter.navigationCommand("iniciar treino"))
        assertEquals(ChatNavAction.Train, ChatRouter.navigationCommand("quero treinar"))
        assertEquals(ChatNavAction.Present, ChatRouter.navigationCommand("ir para o palco"))
        assertEquals(ChatNavAction.Present, ChatRouter.navigationCommand("abrir o palco"))
    }

    @Test
    fun proximoPassoEMeGuie() {
        assertEquals(ChatNavAction.NextStep, ChatRouter.navigationCommand("qual o próximo passo?"))
        assertEquals(ChatNavAction.NextStep, ChatRouter.navigationCommand("me guie"))
        assertEquals(ChatNavAction.NextStep, ChatRouter.navigationCommand("o que faço agora?"))
        // "próximo" sozinho continua sendo navegação de seção (fluxo antigo).
        assertNull(ChatRouter.navigationCommand("próximo"))
    }

    @Test
    fun perguntasNaoSequestramNavegacao() {
        for (t in listOf(
            "o que tem no ponto 2?",
            "como abrir o ponto 2?",
            "quais são as fontes?",
            "como apresentar melhor?",
            "explique o ponto 2",
            "onde fica a introdução?",
        )) {
            assertNull(t, ChatRouter.navigationCommand(t))
        }
    }

    @Test
    fun routeDevolveNavigateAntesDaOratoria() {
        val r = ChatRouter.route("abrir o ponto 2", null, null, null)
        assertTrue(ChatRouter.describe(r), r is Route.Navigate)
        assertEquals(
            ChatNavAction.Section(number = 2, byTopic = false),
            (r as Route.Navigate).action,
        )
    }

    // ---------- sugestão de próximo passo ----------

    private fun section(
        id: String,
        order: Int,
        role: SectionRole,
        title: String,
        html: String = "",
    ) = SpeechSection(
        id = id,
        noteId = "note",
        order = order,
        role = role,
        title = title,
        minutes = 3,
        contentHtml = html,
        bibleRefs = emptyList(),
        publicationRefs = emptyList(),
        methodPrinciple = null,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun semSecoesNaoHaSugestao() {
        assertNull(nextStepSuggestion(emptyList(), null))
    }

    @Test
    fun primeiroTrechoVazioViraAbrirIntroducao() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução"),
            section("b1", 1, SectionRole.BODY, "Ponto 1", "<p>texto</p>"),
            section("c", 2, SectionRole.CONCLUSION, "Conclusão", "<p>texto</p>"),
        )
        val s = nextStepSuggestion(sections, focusedSectionId = null)!!
        assertEquals("Abrir Introdução", s.label)
        assertEquals(
            ResolvedChatNav.OpenSection("i", "Introdução", 1),
            s.action,
        )
    }

    @Test
    fun pontoVazioDepoisDaIntroducao() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução", "<p>texto</p>"),
            section("b1", 1, SectionRole.BODY, "Cultivando o interesse"),
            section("b2", 2, SectionRole.BODY, "Ponto 2", "<p>texto</p>"),
            section("c", 3, SectionRole.CONCLUSION, "Conclusão", "<p>texto</p>"),
        )
        val s = nextStepSuggestion(sections, focusedSectionId = null)!!
        assertEquals("Abrir Ponto 1", s.label)
        assertEquals(
            ResolvedChatNav.OpenSection("b1", "Cultivando o interesse", 2),
            s.action,
        )
    }

    @Test
    fun trechoVazioEmFocoViraDesenvolver() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução", "<p>texto</p>"),
            section("b1", 1, SectionRole.BODY, "Ponto 1"),
        )
        val s = nextStepSuggestion(sections, focusedSectionId = "b1")!!
        assertEquals("Desenvolver Ponto 1", s.label)
        assertEquals(ResolvedChatNav.DevelopCurrent("b1", "Ponto 1"), s.action)
    }

    @Test
    fun htmlVazioNaoContaComoTexto() {
        val sections = listOf(
            section("b1", 0, SectionRole.BODY, "Ponto 1", "<p><br></p>&nbsp;"),
        )
        val s = nextStepSuggestion(sections, focusedSectionId = null)!!
        assertEquals("Abrir Ponto 1", s.label)
    }

    @Test
    fun tudoPreenchidoSugereTreino() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução", "<p>texto</p>"),
            section("b1", 1, SectionRole.BODY, "Ponto 1", "<p>texto</p>"),
            section("c", 2, SectionRole.CONCLUSION, "Conclusão", "<p>texto</p>"),
        )
        val s = nextStepSuggestion(sections, focusedSectionId = "b1")!!
        assertEquals("Iniciar Treino", s.label)
        assertEquals(ResolvedChatNav.StartTrain, s.action)
    }

    @Test
    fun textoCompletoComPalcoProntoOfereceApresentarETreinar() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução", "<p>texto</p>"),
            section("b1", 1, SectionRole.BODY, "Ponto 1", "<p>texto</p>"),
            section("c", 2, SectionRole.CONCLUSION, "Conclusão", "<p>texto</p>"),
        )
        val practice = PracticeSnapshot(
            noteId = "note",
            hasTrainingText = true,
            hasTextBySectionId = sections.associate { it.id to true },
            presentState = PracticePresentState.READY,
            presentLabel = "Mapa mental pronto",
        )
        val s = nextStepSuggestion(sections, focusedSectionId = "b1", practice = practice)!!
        assertEquals("Apresentar", s.label)
        assertEquals(ResolvedChatNav.OpenPresent, s.action)
        assertEquals(ResolvedChatNav.StartTrain, s.secondaryAction)
    }

    @Test
    fun sessaoSalvaSugereContinuarNoPalco() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução", "<p>texto</p>"),
            section("b1", 1, SectionRole.BODY, "Ponto 1", "<p>texto</p>"),
            section("c", 2, SectionRole.CONCLUSION, "Conclusão", "<p>texto</p>"),
        )
        val practice = PracticeSnapshot(
            noteId = "note",
            hasTrainingText = true,
            hasTextBySectionId = sections.associate { it.id to true },
            presentState = PracticePresentState.READY,
            presentLabel = "Mapa mental pronto",
            savedSectionIndex = 1,
            savedSectionTitle = "Ponto 1",
            savedElapsedSec = 312,
        )
        val s = nextStepSuggestion(sections, focusedSectionId = null, practice = practice)!!
        assertEquals("Continuar no Palco", s.label)
        assertTrue(s.detail, s.detail.contains("seção 2"))
        assertTrue(s.detail, s.detail.contains("5:12"))
        assertEquals(ResolvedChatNav.OpenPresent, s.action)
        assertEquals(ResolvedChatNav.StartTrain, s.secondaryAction)
    }

    @Test
    fun subpontosContamComoTrechoPreenchido() {
        val sections = listOf(
            section("i", 0, SectionRole.INTRO, "Introdução", "<p>texto</p>"),
            // S-34: contentHtml vazio, mas o subponto tem desenvolvimento.
            section("b1", 1, SectionRole.BODY, "Ponto 1", ""),
            section("c", 2, SectionRole.CONCLUSION, "Conclusão", "<p>texto</p>"),
        )
        val practice = PracticeSnapshot(
            noteId = "note",
            hasTrainingText = true,
            hasTextBySectionId = mapOf("i" to true, "b1" to true, "c" to true),
            presentState = PracticePresentState.MISSING,
            presentLabel = "Falta gerar o mapa mental",
        )
        val s = nextStepSuggestion(sections, focusedSectionId = null, practice = practice)!!
        assertEquals("Iniciar Treino", s.label)
        assertEquals(ResolvedChatNav.StartTrain, s.action)
        assertEquals(ResolvedChatNav.OpenPresent, s.secondaryAction)
    }

    @Test
    fun textoCompletoSemPalcoAindaOfereceAsDuasAcoes() {
        val sections = listOf(
            section("b1", 0, SectionRole.BODY, "Ponto 1", "<p>texto</p>"),
        )
        val s = nextStepSuggestion(sections, focusedSectionId = null)!!
        assertEquals("Iniciar Treino", s.label)
        assertEquals(ResolvedChatNav.StartTrain, s.action)
        assertEquals(ResolvedChatNav.OpenPresent, s.secondaryAction)
    }

    @Test
    fun rotuloDoBotaoSegueAAcao() {
        assertEquals("Abrir", nextStepActionLabel(ResolvedChatNav.OpenSection("b1", "Ponto 1", 2)))
        assertEquals("Desenvolver", nextStepActionLabel(ResolvedChatNav.DevelopCurrent("b1", "Ponto 1")))
        assertEquals("Iniciar Treino", nextStepActionLabel(ResolvedChatNav.StartTrain))
        assertEquals("Ir para o Palco", nextStepActionLabel(ResolvedChatNav.OpenPresent))
    }
}
