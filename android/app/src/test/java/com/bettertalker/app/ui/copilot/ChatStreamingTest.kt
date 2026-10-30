package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.TurnPhase
import com.bettertalker.app.ui.components.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamingTest {

    @Test
    fun streamingBlocks_parseiaTituloListaECard() {
        val blocks = streamingBlocks(
            "# Título\n\n- um\n- dois\n\n<texto-do-card>texto do card</texto-do-card>",
        )
        assertTrue(blocks.any { it is MdBlock.Title })
        assertEquals(2, blocks.filterIsInstance<MdBlock.Bullet>().size)
        assertEquals(
            "texto do card",
            blocks.filterIsInstance<MdBlock.TextForCard>().single().text,
        )
    }

    @Test
    fun streamingBlocks_cardSemFechar_aindaViraShell() {
        val blocks = streamingBlocks("<texto-do-card>parcial sem fechar")
        val card = blocks.filterIsInstance<MdBlock.TextForCard>().single()
        assertEquals("parcial sem fechar", card.text)
        // O shell é o mesmo bloco do card final; a tag não vaza para o texto.
        assertTrue(!card.text.contains("texto-do-card"))
        // Ação de inserir/substituir só existe no card final (onUseInCard
        // passado pelo MessageBubble; o streaming passa null).
        assertEquals(1, blocks.size)
    }

    @Test
    fun streamingBlocks_cardAbertoMultilinha_mantemParcial() {
        val blocks = streamingBlocks("<texto-do-card>\nlinha 1\nlinha 2")
        val card = blocks.filterIsInstance<MdBlock.TextForCard>().single()
        assertEquals("linha 1\nlinha 2", card.text)
    }

    @Test
    fun streamingBlocks_cardFechadoComResto_separaCardETexto() {
        val blocks = streamingBlocks("<texto-do-card>card</texto-do-card> resto")
        assertEquals("card", blocks.filterIsInstance<MdBlock.TextForCard>().single().text)
        assertEquals("resto", blocks.filterIsInstance<MdBlock.Para>().single().text)
    }

    @Test
    fun cancelledPartialComMarkdown_renderizaBlocosNaoTextoCru() {
        // Amostra no formato do parcial real congelado: título, negrito e lista.
        val partial = "## Introdução\n\n**Esperança** é o combustível.\n\n- um\n- dois"
        val blocks = cancelledPartialBlocks(partial)
        assertTrue(blocks.any { it is MdBlock.Title })
        assertTrue(blocks.any { it is MdBlock.Bullet })
        assertTrue(
            blocks.filterIsInstance<MdBlock.Para>()
                .any { it.text.contains("**Esperança**") },
        )
    }

    @Test
    fun cancelledPartialSoComMarcadores_naoRenderiza() {
        assertTrue(cancelledPartialBlocks("〈sugestão〉  〈/sugestão〉").isEmpty())
    }

    @Test
    fun streamThrottleFor_terminalRenderizaImediato() {
        assertEquals(0L, streamThrottleFor(TurnPhase.DONE))
        assertEquals(0L, streamThrottleFor(TurnPhase.CANCELLED))
        assertEquals(0L, streamThrottleFor(TurnPhase.ERROR))
        assertEquals(STREAM_THROTTLE_MS, streamThrottleFor(TurnPhase.WAITING))
        assertEquals(STREAM_THROTTLE_MS, streamThrottleFor(TurnPhase.REASONING))
        assertEquals(STREAM_THROTTLE_MS, streamThrottleFor(TurnPhase.STREAMING))
    }

    @Test
    fun streamingBlocks_vazioNaoRenderiza() {
        assertTrue(streamingBlocks("").isEmpty())
        assertTrue(streamingBlocks("   \n  ").isEmpty())
    }

    @Test
    fun throttleEhCemMs() {
        assertEquals(100L, STREAM_THROTTLE_MS)
    }

    @Test
    fun turnStatusAnnouncement_mapeiaFases() {
        assertEquals("Pensando", turnStatusAnnouncement(TurnPhase.WAITING))
        assertEquals("Pensando", turnStatusAnnouncement(TurnPhase.REASONING))
        assertEquals("Gerando", turnStatusAnnouncement(TurnPhase.STREAMING))
        assertEquals("Interrompido", turnStatusAnnouncement(TurnPhase.CANCELLED))
        assertEquals("Resultado pronto", turnStatusAnnouncement(TurnPhase.DONE))
        assertNull(turnStatusAnnouncement(TurnPhase.ERROR))
    }

    @Test
    fun unseenAnnouncement_singularEPlural() {
        assertEquals("1 nova mensagem", unseenAnnouncement(1))
        assertEquals("3 novas mensagens", unseenAnnouncement(3))
    }
}
