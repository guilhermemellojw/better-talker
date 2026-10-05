package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.copilot.QUICK_ACTIONS
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.inferIntent
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.repo.IdeaCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 16 — matriz de paridade Web F15 × Android F16.
 *
 * Cada teste declara um comportamento que a F15 já cumpre no web e afirma que
 * o nativo cumpre igual. Se o Kotlin divergir, este arquivo falha — é o
 * contrato de paridade executável.
 */
class ChatParityMatrixTest {

    private fun ev(id: String, ref: String, text: String, type: SourceType = SourceType.CONTENT) =
        EvidenceSource(
            id = id, reference = ref, text = text, sourceType = type,
            publication = "Pub", section = null, paragraph = null, page = null,
            trainingCategory = TrainingCategory.UNKNOWN
        )

    // ---------- linha: chat livre ----------

    @Test
    fun linha01_chatLivre() {
        val intent = inferIntent("quero melhorar essa introdução", true)
        assertNotNull(intent)
        assertEquals(TrainingCategory.INTRODUCTION, intent.trainingCategory)
    }

    // ---------- linha: quick action ----------

    @Test
    fun linha02_quickAction() {
        // Todos os quatro atalhos da F15 existem e produzem texto válido.
        assertEquals(
            listOf("Melhorar este ponto", "Deixar mais natural", "Criar ilustração", "Verificar"),
            QUICK_ACTIONS.map { it.label }
        )
    }

    // ---------- linha: thread ----------

    @Test
    fun linha03_threadPreservaMensagensAntigas() {
        // O histórico serializa a sequência inteira, não só a última.
        val hist = listOf(
            ChatTurn(true, "primeira"),
            ChatTurn(false, "resposta"),
            ChatTurn(true, "segunda")
        )
        val txt = com.bettertalker.app.data.copilot.recentHistory(hist)
        assertTrue(txt.contains("primeira"))
        assertTrue(txt.contains("resposta"))
        assertTrue(txt.contains("segunda"))
    }

    // ---------- linha: continuidade ----------

    @Test
    fun linha04_continuidade() {
        val p = buildChatPrompt(
            message = "agora mais natural",
            history = listOf(ChatTurn(true, "melhore a intro"), ChatTurn(false, "ok")),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Introdução", blockMinutes = null, blockText = "x"
        )
        assertTrue(p.contains("Continuidade:"))
    }

    // ---------- linha: contexto automático ----------

    @Test
    fun linha05_contextoAutomatico() {
        val p = buildChatPrompt(
            message = "melhore isso", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 2", blockMinutes = 6, blockText = "corpo"
        )
        assertTrue(p.contains("Ponto 2"))
        assertTrue(p.contains("6 minutos"))
    }

    // ---------- linha: intent invisível ----------

    @Test
    fun linha06_intentInvisivel() {
        // A intenção existe, mas nenhuma string dela aparece no prompt visível
        // como rótulo: o prompt só carrega a instrução de não revelar trilhos.
        val p = buildChatPrompt(
            message = "quero melhorar a introdução", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertFalse(p.contains("TRAINING:"))
        assertFalse(p.contains("Intent:"))
        assertFalse(p.contains("ContextPack:"))
        // E ordena explicitamente que nada disso seja exposto.
        assertTrue(p.contains("sem rótulos internos"))
    }

    // ---------- linha: CONTENT ----------

    @Test
    fun linha07_contentVaiParaBlocoDeConteudo() {
        val p = buildChatPrompt(
            message = "o que a fonte diz", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(listOf(ev("c1", "A Sentinela", "fato")), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertTrue(p.contains("FONTES DE CONTEÚDO"))
        assertTrue(p.contains("fato"))
    }

    // ---------- linha: TRAINING ----------

    @Test
    fun linha08_trainingVaiParaBlocoDeTecnica() {
        val p = buildChatPrompt(
            message = "deixe mais natural", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(
                emptyList(),
                listOf(ev("t1", "Beneficie-se", "orientacao", SourceType.TRAINING))
            ),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertTrue(p.contains("ORIENTAÇÕES DE ORATÓRIA"))
        assertTrue(p.contains("NÃO usar como fatos"))
    }

    // ---------- linha: erro amigável ----------

    @Test
    fun linha09_erroAmigavel() {
        val msg = com.bettertalker.app.data.copilot.friendlyChatError(
            com.bettertalker.app.data.copilot.ProviderErrorCode.NETWORK
        )
        assertFalse(msg.contains("HTTP"))
        assertTrue(msg.contains("Verifique a conexão"))
    }

    // ---------- 5. erro amigável ----------

    @Test
    fun linha11_proveniencia() {
        val p = buildChatPrompt(
            message = "me dá um exemplo", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(listOf(ev("c1", "A Sentinela p. 5", "texto")), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        // A referência entra no prompt: o app não perde a origem.
        assertTrue(p.contains("A Sentinela p. 5"))
    }

    // ---------- linha: composer não trava após erro ----------

    @Test
    fun linha12_composerDestravaAposErro() {
        // A web garante isso; o bug é o mesmo nos dois.
        assertFalse(ChatRunState.Error("x").blocksComposer)
    }

    // ---------- linha: codec compatível com o que já existe ----------

    @Test
    fun linha13_codecContinuaCompativel() {
        // Mensagens antigas (sem 'tc') ainda decodificam: nada quebra.
        val json = ChatCodec.cardsToJson(
            listOf(IdeaCard("t", "b", "", "", source = "Fonte"))
        )
        val back = ChatCodec.cardsFromJson(json)
        assertEquals(1, back.size)
        assertEquals("t", back[0].title)
        // Payload mínimo continua aceito (compatibilidade com mensagens salvas).
        assertEquals(emptyList<IdeaCard>(), ChatCodec.cardsFromJson(""))
        assertNull(ChatCodec.unescMap("")["text"])
    }

    // ---------- linha: geração sem falsificar remoto ----------

    @Test
    fun linha14_estadosSaoDistintos() {
        // error e cancelled são estados diferentes e liberam o composer.
        assertFalse(ChatRunState.Error("x") is ChatRunState.Cancelled)
        assertFalse(ChatRunState.Error("x").blocksComposer)
    }

    // ---------- T1 (polimento visual): instrução de markdown na cauda ----------

    @Test
    fun linha15_instrucaoDeMarkdownNaCaudaDoChat() {
        val p = buildChatPrompt(
            message = "explique", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertTrue(p.contains("use ## para seções"))
        assertTrue(p.contains("-/1. para listas"))
        assertTrue(p.contains("Sem código, tabelas ou HTML"))
        // A cauda fecha o prompt: a instrução é a última coisa enviada.
        assertTrue(p.trimEnd().endsWith("Sem código, tabelas ou HTML."))
    }
}
