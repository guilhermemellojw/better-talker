package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Origem honesta das mensagens: default COPILOT, LOCAL só quando
 * registrado. Histórico antigo (sem chave) decodifica como COPILOT.
 */
class MessageOriginTest {

    @Test
    fun chatItem_defaultOrigin_isCopilot() {
        val item = CopilotViewModel.ChatItem(id = "1", fromMe = false, kind = "text", text = "oi")
        assertEquals(MessageOrigin.COPILOT, item.origin)
    }

    @Test
    fun messageOriginOf_missingKey_defaultsToCopilot() {
        assertEquals(MessageOrigin.COPILOT, messageOriginOf(mapOf("text" to "oi")))
    }

    @Test
    fun messageOriginOf_garbage_defaultsToCopilot() {
        assertEquals(MessageOrigin.COPILOT, messageOriginOf(mapOf("origin" to "???")))
    }

    @Test
    fun messageOriginOf_localRoundTrips() {
        assertEquals(MessageOrigin.LOCAL, messageOriginOf(mapOf("origin" to "LOCAL")))
    }
}
