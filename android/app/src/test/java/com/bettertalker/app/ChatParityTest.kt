package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatAction
import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.copilot.FOLLOW_UP_SUGGESTIONS
import com.bettertalker.app.data.copilot.MAX_HISTORY_CHARS
import com.bettertalker.app.data.copilot.MAX_HISTORY_MESSAGES
import com.bettertalker.app.data.copilot.ProviderErrorCode
import com.bettertalker.app.data.copilot.QUICK_ACTIONS
import com.bettertalker.app.data.copilot.chatBriefToText
import com.bettertalker.app.data.copilot.contextLabel
import com.bettertalker.app.data.copilot.friendlyChatError
import com.bettertalker.app.data.copilot.inferIntent
import com.bettertalker.app.data.copilot.recentHistory
import com.bettertalker.app.data.copilot.validateOutgoingMessage
import com.bettertalker.app.data.domain.TrainingCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 16 — paridade do chat nativo com a F15.
 *
 * Estes testes travam o CONTRATO que o web já cumpre, para que o Kotlin não
 * diverga por acidente. Mesma função, mesmo nome, mesmo limite.
 */
class ChatEngineParityTest {

    // ---------- 1. mensagem normal e vazia ----------

    @Test
    fun mensagemNormalPassaNaValidacao() {
        assertEquals("Quero melhorar essa introdução.", validateOutgoingMessage("  Quero melhorar essa introdução.  "))
    }

    @Test
    fun mensagemVaziaEBloqueada() {
        assertNull(validateOutgoingMessage(""))
        assertNull(validateOutgoingMessage("   "))
        assertNull(validateOutgoingMessage("\n\t "))
    }

    // ---------- 2. intent é interna, nunca exposta ----------

    @Test
    fun intentDeIntroducaoEHook() {
        val i = inferIntent("quero melhorar essa introdução", true)
        assertEquals(ChatAction.HOOK, i.action)
        assertEquals(TrainingCategory.INTRODUCTION, i.trainingCategory)
        assertFalse(i.verification)
    }

    @Test
    fun intentDeNaturalidade() {
        val i = inferIntent("deixe mais natural", false)
        assertEquals(ChatAction.REWRITE, i.action)
        assertEquals(TrainingCategory.NATURALNESS, i.trainingCategory)
    }

    @Test
    fun intentDeIlustracao() {
        val i = inferIntent("crie uma ilustração para esse ponto", true)
        assertEquals(TrainingCategory.ILLUSTRATION, i.trainingCategory)
    }

    @Test
    fun intentDeVerificacaoEFactual() {
        val i = inferIntent("isso está certo?", true)
        assertTrue(i.verification)
        // Verificação NUNCA usa BE/TH como prova: categoria vazia.
        assertNull(i.trainingCategory)
    }

    @Test
    fun intentGenericoNaoPuxaTraining() {
        val i = inferIntent("o que diz a publicação sobre caridade?", true)
        assertNull(i.trainingCategory)
    }

    @Test
    fun intentDeMelhorarDependeDoPrimeiroTurno() {
        // "melhorar" sozinho muda de categoria conforme o contexto da conversa.
        assertEquals(TrainingCategory.CLARITY, inferIntent("melhorar isso", true).trainingCategory)
        assertEquals(TrainingCategory.NATURALNESS, inferIntent("melhorar isso", false).trainingCategory)
    }

    @Test
    fun intentDeApresentacaoPegaDelivery() {
        assertEquals(TrainingCategory.DELIVERY, inferIntent("como apresentar isso no palco", true).trainingCategory)
    }

    @Test
    fun intentIgnoraAcentoECaixa() {
        assertEquals(
            inferIntent("deixe mais natural", true),
            inferIntent("DEIXE MAIS NATURAL", true)
        )
    }

    // ---------- 3. histórico e continuidade ----------

    @Test
    fun historicoRespeitaJanela() {
        val many = (1..20).map { ChatTurn(it % 2 == 1, "msg $it") }
        val out = recentHistory(many)
        assertTrue(out.contains("msg 20"))
        // A janela é as últimas 6: a msg 5 caiu, a msg 15 ficou.
        assertFalse(out.contains("msg 5\n"))
        assertTrue(out.contains("msg 15"))
        // Só as últimas MAX_HISTORY_MESSAGES entram.
        assertEquals(MAX_HISTORY_MESSAGES, out.lines().count { it.startsWith("Usuário:") || it.startsWith("Copilot:") })
    }

    @Test
    fun historicoCortaMensagemLonga() {
        val long = ChatTurn(true, "x".repeat(MAX_HISTORY_CHARS + 400))
        val out = recentHistory(listOf(long))
        assertTrue(out.contains("…"))
        assertTrue(out.length < MAX_HISTORY_CHARS + 200)
    }

    @Test
    fun historicoRotulaQuemFala() {
        val out = recentHistory(listOf(ChatTurn(true, "oi"), ChatTurn(false, "olá")))
        assertTrue(out.contains("Usuário: oi"))
        assertTrue(out.contains("Copilot: olá"))
    }

    @Test
    fun historicoVazioNaoGeraSecao() {
        assertEquals("", recentHistory(emptyList()))
        assertEquals("", recentHistory(listOf(ChatTurn(true, "  "))))
    }

    @Test
    fun primeiraMensagemNaoCarregaContinuidade() {
        val txt = chatBriefToText("melhore isso", emptyList(), isFirstMessage = true)
        assertFalse(txt.contains("Continuidade:"))
        assertTrue(txt.contains("Mensagem do usuário: \"melhore isso\""))
    }

    @Test
    fun continuidadeApareceNaSegundaMensagem() {
        val hist = listOf(ChatTurn(true, "quero melhorar a intro"), ChatTurn(false, "posso melhorar o gancho"))
        val txt = chatBriefToText("deixe mais natural", hist, isFirstMessage = false)
        assertTrue(txt.contains("CONVERSA ANTERIOR"))
        assertTrue(txt.contains("Continuidade:"))
        assertTrue(txt.contains("isso/essa parte"))
        assertTrue(txt.contains("quero melhorar a intro"))
    }

    // ---------- 4. quick actions pelo mesmo caminho ----------

    @Test
    fun quickActionsSaoMensagensValidas() {
        assertEquals(4, QUICK_ACTIONS.size)
        for (qa in QUICK_ACTIONS) {
            // Nenhum atalho é comando vazio: todos enviam texto de verdade.
            assertEquals(qa.message, validateOutgoingMessage(qa.message))
            assertTrue(qa.label.isNotBlank())
        }
    }

    @Test
    fun quickActionTemIntencaoPropria() {
        val natural = QUICK_ACTIONS.first { it.id == "natural" }
        assertEquals(TrainingCategory.NATURALNESS, inferIntent(natural.message, true).trainingCategory)
        val verify = QUICK_ACTIONS.first { it.id == "verify" }
        assertTrue(inferIntent(verify.message, true).verification)
    }

    @Test
    fun followUpsSaoMensagensValidas() {
        assertTrue(FOLLOW_UP_SUGGESTIONS.isNotEmpty())
        for (f in FOLLOW_UP_SUGGESTIONS) {
            assertNotNull(validateOutgoingMessage(f))
        }
    }

    // ---------- 6. erro amigável nunca vaza técnica ----------

    @Test
    fun erroAmigavelNaoExpoeDetalhesTecnicos() {
        val forbidden = listOf("HTTP", "503", "ProviderError", "AbortError", "stack", "Exception")
        for (code in ProviderErrorCode.values()) {
            val msg = friendlyChatError(code)
            for (f in forbidden) {
                assertFalse("$code não pode conter $f", msg.contains(f, ignoreCase = true))
            }
            assertTrue(msg.isNotBlank())
        }
    }

    @Test
    fun erroPadraoTemAcaoClara() {
        val msg = friendlyChatError(ProviderErrorCode.INVALID_RESPONSE)
        assertTrue(msg.contains("Tente novamente"))
    }

    @Test
    fun erroDeCancelamentoDizQueNadaMudou() {
        val msg = friendlyChatError(ProviderErrorCode.CANCELLED)
        assertTrue(msg.contains("Nada foi alterado"))
    }

    @Test
    fun erroDeTimeoutPedeConexao() {
        assertTrue(friendlyChatError(ProviderErrorCode.TIMEOUT).contains("conexão"))
    }

    // ---------- 7. estados de geração ----------

    @Test
    fun composerSoBloqueiaDuranteGeracao() {
        assertTrue(ChatRunState.Sending.blocksComposer)
        assertTrue(ChatRunState.Generating.blocksComposer)
        assertFalse(ChatRunState.Idle.blocksComposer)
        assertFalse(ChatRunState.Success.blocksComposer)
    }

    @Test
    fun composerNaoFicaBloqueadoAposErro() {
        // Este é o bug que a F15 encontrou e corrigiu.
        assertFalse(ChatRunState.Error("falhou").blocksComposer)
        assertFalse(ChatRunState.Cancelled().blocksComposer)
    }

    @Test
    fun erroECanceladoSaoEstadosTerminais() {
        assertTrue(ChatRunState.Error("x").isTerminal)
        assertTrue(ChatRunState.Cancelled().isTerminal)
        assertTrue(ChatRunState.Success.isTerminal)
        assertFalse(ChatRunState.Generating.isTerminal)
    }

    @Test
    fun todosOsEstadosTtemTextoOuSaoConhecidos() {
        val states: List<ChatRunState> = listOf(
            ChatRunState.Idle, ChatRunState.Sending, ChatRunState.Generating,
            ChatRunState.Success, ChatRunState.Error("x"),
            ChatRunState.Cancelled()
        )
        assertEquals(6, states.size)
        for (s in states) {
            if (s is ChatRunState.Error) assertTrue(s.message.isNotBlank())
        }
    }

    // ---------- 8. rótulo de contexto ----------

    @Test
    fun contextoUsaBlocoAntesDoDiscurso() {
        assertEquals("Introdução", contextLabel("Introdução", "Meu discurso"))
        assertEquals("Meu discurso", contextLabel(null, "Meu discurso"))
        assertEquals("sem contexto", contextLabel(null, null))
        assertEquals("Introdução", contextLabel("  Introdução  ", "Meu discurso"))
    }
}
