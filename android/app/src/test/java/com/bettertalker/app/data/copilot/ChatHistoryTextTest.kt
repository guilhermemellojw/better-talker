package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.util.RefDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 1 — histórico fiel: cada kind persistido vira texto legível.
 */
class ChatHistoryTextTest {

    @Test
    fun textoSimplesVoltaComoTexto() {
        val payload = ChatCodec.escMap(mapOf("text" to "Vamos desenvolver o ponto 2."))
        assertEquals("Vamos desenvolver o ponto 2.", readableChatTurn("text", payload))
    }

    @Test
    fun ideiasViramTitulosComMarcador() {
        val cards = listOf(
            IdeaCard("Pergunta inicial", "corpo", "", "", "fonte", "Introdução"),
            IdeaCard("Aplicação prática", "corpo", "", "", "fonte", "Conclusão"),
        )
        val payload = ChatCodec.escMap(mapOf(
            "section" to "Introdução",
            "cards" to ChatCodec.cardsToJson(cards),
        ))
        val text = readableChatTurn("ideas", payload)
        assertTrue(text.contains("Pergunta inicial"))
        assertTrue(text.contains("Aplicação prática"))
        assertTrue(text.contains("(ideias)"))
        assertFalse(text == "ideas")
    }

    @Test
    fun refsViramRotulos() {
        val detected = listOf(
            RefDetector.DetectedRef(
                raw = "w25.07 § 8",
                kind = RefDetector.Kind.MAGAZINE,
                pubKey = "w",
                editionKey = "w|2025|7",
                label = "A Sentinela julho de 2025",
                page = 22,
                paragraph = 8,
            ),
            RefDetector.DetectedRef(
                raw = "lmd lição 7 ponto 4",
                kind = RefDetector.Kind.BOOK,
                pubKey = "lmd",
                editionKey = "book|lmd",
                label = "Apostila",
                chapter = "lição 7",
                paragraph = 4,
            ),
        )
        val payload = ChatCodec.escMap(mapOf(
            "title" to "Referências da nota",
            "detected" to RefDetector.detectedToJson(detected),
        ))
        val text = readableChatTurn("refs", payload)
        assertTrue(text.contains("w25.07 § 8"))
        assertTrue(text.contains("lmd lição 7 ponto 4"))
        assertFalse(text == "refs")
    }

    @Test
    fun sectionsViramTitulos() {
        val payload = ChatCodec.escMap(mapOf(
            "title" to "Esboço — toque em Gerar:",
            "items" to ChatCodec.sectionsToJson(listOf(
                ChatCodec.SecItem("Introdução", 3),
                ChatCodec.SecItem("Desenvolvimento", 10),
            )),
        ))
        val text = readableChatTurn("sections", payload)
        assertTrue(text.contains("Introdução"))
        assertTrue(text.contains("Desenvolvimento"))
        assertTrue(text.contains("(seções)"))
    }

    @Test
    fun draftViraTituloEPrimeirasLinhas() {
        val payload = ChatCodec.draftToJson("Rascunho", listOf(
            ChatCodec.DraftItem(
                title = "Ponto 1",
                minutes = 4,
                included = true,
                body = "Primeira linha do texto.\nSegunda linha.",
            ),
        ))
        val text = readableChatTurn("draft", payload)
        assertTrue(text.contains("Rascunho"))
        assertTrue(text.contains("Ponto 1"))
        assertTrue(text.contains("Primeira linha do texto."))
    }

    @Test
    fun kindDesconhecidoNuncaViraTexto() {
        val payload = ChatCodec.escMap(mapOf("text" to "conteúdo real"))
        assertEquals("conteúdo real", readableChatTurn("kind_novo", payload))
        assertTrue(readableChatTurn("kind_sem_texto", "{}").isEmpty())
    }

    @Test
    fun rotuloDeFocoDistingueTopicoEDiscursoInteiro() {
        assertEquals("Discurso inteiro", chatFocusLabel(null, null))
        assertEquals("Tópico: Introdução", chatFocusLabel("sec-1", "Introdução"))
        assertEquals("Tópico: seção", chatFocusLabel("sec-2", null))
    }

    @Test
    fun turnoRotuladoMantemPerguntaEPermiteContinuidade() {
        val labeled = labelChatTurn(
            "Quer que eu escreva o bloco nessa linha?",
            "sec-1",
            "Ponto 2",
        )
        assertTrue(labeled.startsWith("[Tópico: Ponto 2]"))
        assertTrue(labeled.contains("Quer que eu escreva o bloco nessa linha?"))
    }
}
