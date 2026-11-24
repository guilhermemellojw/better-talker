package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.Message
import com.bettertalker.app.data.copilot.ConversationalReferentRecovery.TargetState
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.MentorContinuityRouting.Action
import com.bettertalker.app.data.copilot.MentorContinuityRouting.Resolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MENTOR-INTEGRATION-P11 — testes da FRONTEIRA produtiva chamada por send().
 * Não são testes end-to-end de ViewModel: exercitam a função exata usada no
 * seam, com loaders injetados (inclusive falhas e cancelamento).
 */
class MentorContinuityBoundaryTest {

    private val titles = mapOf("sec-1" to "Ponto 1")

    private fun cardMsg(id: String = "m1", status: CardStatus = CardStatus.AVAILABLE, createdAt: Long = 100L): Message {
        val payload = CardPayload(
            targetKind = CardTargetKind.SECTION, targetId = "sec-1",
            targetLabel = "Romanos 8", baseHash = "h", status = status,
        ).toMap().toMutableMap().apply {
            put("text", "<texto-do-card>Texto do card.</texto-do-card>")
        }
        return Message(id, "n1", "sec-1", payload, createdAt)
    }

    private suspend fun boundary(
        request: String = "Explique melhor",
        loadMessages: suspend () -> List<Message> = { listOf(cardMsg()) },
        loadTargetState: suspend (CardTargetKind, String) -> TargetState? = { _, _ -> TargetState(true) },
    ) = MentorContinuityRouting.resolveFromContext(
        request = request, noteId = "n1", scope = "sec-1", focus = Focus.SECTION,
        suggestion = null, suggestionNoteId = null, suggestionSectionId = null,
        lastResponse = null, sectionTitles = titles,
        loadMessages = loadMessages, loadTargetState = loadTargetState,
    )

    // 1 — falha na leitura do histórico → indisponível, sem ProceedNormal silencioso
    @Test
    fun d01_historicoIndisponivel_failClosed() = runBlocking {
        val r = boundary(loadMessages = { throw IllegalStateException("texto pessoal secreto") })
        assertTrue(r is Resolution.Unavailable)
        assertFalse("diagnóstico não expõe conteúdo", (r as Resolution.Unavailable).reason.contains("secreto"))
        assertTrue(MentorContinuityRouting.needsReferent("Explique melhor"))
    }

    // 2 — falha na recuperação da seção → indisponível
    @Test
    fun d02_falhaNaSecao_indisponivel() = runBlocking {
        val r = boundary(loadTargetState = { _, _ -> throw RuntimeException("falha de leitura") })
        assertTrue(r is Resolution.Unavailable)
    }

    // 3 — APPLIED sem reinserção (na fronteira real)
    @Test
    fun d03_applied_semReinsercao() = runBlocking {
        val r = boundary(loadMessages = { listOf(cardMsg(status = CardStatus.APPLIED)) })
        assertTrue(r is Resolution.Ok)
        val ok = r as Resolution.Ok
        assertTrue(ok.plan.action is Action.DeepenCard)
        assertEquals(CardStatus.APPLIED, (ok.plan.action as Action.DeepenCard).card.status)
        assertFalse(ok.plan.capabilities!!.insertable)
    }

    // 4 — ambiguidade → esclarecimento, sem chamada remota
    @Test
    fun d04_ambiguidade_esclarecimentoLocal() = runBlocking {
        val r = boundary(loadMessages = { listOf(cardMsg(id = "m1", createdAt = 100L), cardMsg(id = "m2", createdAt = 100L)) })
        assertTrue(r is Resolution.Ok)
        assertTrue((r as Resolution.Ok).plan.action is Action.Clarify)
    }

    // 5 — pedido explícito preservado (não depende do referente)
    @Test
    fun d05_pedidoExplicito_preservado() = runBlocking {
        assertFalse(MentorContinuityRouting.needsReferent("Desenvolva o ponto 1"))
        assertFalse(MentorContinuityRouting.needsReferent("Escreva o texto"))
        assertTrue(MentorContinuityRouting.needsReferent("Vamos continuar de onde paramos"))
    }

    // 6 — cancelamento propagado, nunca mascarado
    @Test
    fun d06_cancelamento_propaga() = runBlocking {
        var propagated = false
        try {
            boundary(loadMessages = { throw CancellationException("cancelado") })
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
