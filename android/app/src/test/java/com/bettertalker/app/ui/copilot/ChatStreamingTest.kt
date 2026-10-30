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
        assertEquals(
            "parcial sem fechar",
            blocks.filterIsInstance<MdBlock.TextForCard>().single().text,
        )
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
