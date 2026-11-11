package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.library.GlobalLibrarySource
import com.bettertalker.app.domain.planning.GenerationProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt global: títulos/janela curta ou fontes explícitas, nunca dossiê. */
class GlobalAssistantPromptTest {

    @Test
    fun incluiTitulosRecentesSemConteudoDeNota() {
        val prompt = GlobalAssistantPrompt.build(
            userText = "O que é uma boa introdução?",
            recentTitles = listOf("Esperança viva", "Cultivando o interesse"),
        )
        assertTrue(prompt.contains("Esperança viva"))
        assertTrue(prompt.contains("Cultivando o interesse"))
        assertTrue(prompt.contains("O que é uma boa introdução?"))
        assertFalse(prompt.contains("<p>"))
        assertFalse(prompt.contains("dossiê", ignoreCase = true))
    }

    @Test
    fun incluiJanelaCurtaDaConversa() {
        val prompt = GlobalAssistantPrompt.build(
            userText = "E na conclusão?",
            history = listOf(
                GlobalPromptTurn(fromMe = true, text = "Como começar?"),
                GlobalPromptTurn(fromMe = false, text = "Comece com uma pergunta."),
            ),
        )
        assertTrue(prompt.contains("Usuário: Como começar?"))
        assertTrue(prompt.contains("BT: Comece com uma pergunta."))
        assertTrue(prompt.contains("PEDIDO ATUAL:"))
    }

    @Test
    fun incluiFontesComUnidadePaginaEParagrafo() {
        val prompt = GlobalAssistantPrompt.build(
            userText = "O que a Sentinela diz sobre os anjos?",
            sources = listOf(
                GlobalLibrarySource(
                    index = 1,
                    attachmentId = "wp",
                    publicationTitle = "A Sentinela — julho de 2025",
                    unitLabel = "Artigo 1",
                    unitTitle = "A verdade sobre os anjos",
                    page = 5,
                    pageEnd = 5,
                    paragraph = 3,
                    snippet = "Os anjos são mensageiros de Deus.",
                    startOrd = 0,
                    endOrd = 10,
                )
            ),
        )
        assertTrue(prompt.contains("FONTES DA BIBLIOTECA"))
        assertTrue(prompt.contains("[Fonte 1]"))
        assertTrue(prompt.contains("A Sentinela — julho de 2025"))
        assertTrue(prompt.contains("Artigo 1"))
        assertTrue(prompt.contains("pág. 5"))
        assertTrue(prompt.contains("§ 3"))
        assertTrue(prompt.contains("cite [Fonte N]"))
    }

    @Test
    fun notaDeBuscaExplicaQuandoAchouEmOutraPublicacao() {
        val prompt = GlobalAssistantPrompt.build(
            userText = "O que o livro X diz sobre refúgio?",
            libraryNote = "Não encontrei em X; encontrei em Jeremias.",
        )
        assertTrue(prompt.contains("NOTA DE BUSCA"))
        assertTrue(prompt.contains("Não encontrei em X"))
    }

    @Test
    fun systemPromptExigeRespostaCurtaSemInvencaoESemFalsaRecusa() {
        val system = GenerationPrompts
            .systemPromptFor(GenerationProfile.GLOBAL_ASSISTANT)
            .orEmpty()
        assertTrue(system.contains("4 ou 5 frases"))
        assertTrue(system.contains("invente", ignoreCase = true))
        assertTrue(system.contains("FONTES DA BIBLIOTECA"))
        assertTrue(system.contains("não consigo acessar"))
        assertTrue(system.contains("orientação de oratória"))
    }

    @Test
    fun usaTextoCompletoDaUnidadeENaoApenasOSnippet() {
        val full = "A".repeat(1_000) + " Eze. 17:11-15, 17, 18."
        val prompt = GlobalAssistantPrompt.build(
            userText = "Leia a faixa.",
            sources = listOf(
                GlobalLibrarySource(
                    index = 1,
                    attachmentId = "w1710",
                    publicationTitle = "A Sentinela — outubro de 2017",
                    unitLabel = "Página 23",
                    unitTitle = null,
                    page = 23,
                    pageEnd = 23,
                    paragraph = 9,
                    snippet = "snippet curto",
                    promptText = full,
                    startOrd = 0,
                    endOrd = 1,
                )
            ),
        )
        assertTrue(prompt.contains("Eze. 17:11-15, 17, 18."))
        assertFalse(prompt.contains("\"snippet curto\""))
        assertFalse(prompt.contains("truncada"))
    }

    @Test
    fun fonteLongaMarcaOTamanhoVisto() {
        val full = "x".repeat(7_000)
        val prompt = GlobalAssistantPrompt.build(
            userText = "Leia o capítulo.",
            sources = listOf(
                GlobalLibrarySource(
                    index = 1,
                    attachmentId = "cl",
                    publicationTitle = "Achegue-se a Jeová",
                    unitLabel = "Capítulo 2",
                    unitTitle = null,
                    page = 10,
                    pageEnd = 10,
                    paragraph = null,
                    snippet = "curto",
                    promptText = full,
                    startOrd = 0,
                    endOrd = 1,
                )
            ),
        )
        assertTrue(prompt.contains("fonte 1 truncada"))
        assertTrue(prompt.contains("7.000") || prompt.contains("7000"))
    }

}
