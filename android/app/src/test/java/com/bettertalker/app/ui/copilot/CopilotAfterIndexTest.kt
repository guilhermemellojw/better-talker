package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Depois de indexar, o trecho exato da citação entra no prompt — não só a
 * referência. O caminho real (NoteRefsCollector → citedSources) é coberto
 * pelos testes puros; aqui travamos a ponta do prompt.
 */
class CopilotAfterIndexTest {

    private fun cited(reference: String, text: String) = EvidenceSource(
        id = "cite-$reference",
        reference = reference,
        text = text,
        sourceType = SourceType.CONTENT,
        publication = "A Sentinela — julho de 2025",
        section = "22",
        paragraph = 8,
        page = 22,
        score = 1.0,
        foundBy = listOf("cited"),
    )

    @Test
    fun promptContemTrechoDaPublicacaoIndexada() {
        val pack = ContextPack(
            contentSources = listOf(
                cited(
                    "w25.07 22 §§ 8-9",
                    "Sem contentamento, podemos perder nossa alegria e a paz.",
                ),
            ),
            trainingSources = emptyList(),
        )
        val prompt = buildChatPrompt(
            message = "Como desenvolver contentamento?",
            history = emptyList(),
            isFirstMessage = true,
            pack = pack,
            blockTitle = null,
            blockMinutes = null,
            blockText = "Baruque não estava satisfeito com sua situação.",
        )
        assertTrue(prompt.contains("w25.07 22 §§ 8-9"))
        assertTrue(prompt.contains("Sem contentamento, podemos perder nossa alegria e a paz."))
    }

    @Test
    fun referenciaSemTextoEntraComoNaoCitavel() {
        val pack = ContextPack(
            contentSources = emptyList(),
            trainingSources = emptyList(),
            unavailableRefs = listOf("lmd lição 7 ponto 4"),
        )
        val prompt = buildChatPrompt(
            message = "Desenvolva o ponto",
            history = emptyList(),
            isFirstMessage = true,
            pack = pack,
            blockTitle = null,
            blockMinutes = null,
            blockText = "",
        )
        assertTrue(prompt.contains("REFERÊNCIAS DO ESBOÇO SEM TEXTO NO ACERVO"))
        assertTrue(prompt.contains("lmd lição 7 ponto 4"))
    }
}
