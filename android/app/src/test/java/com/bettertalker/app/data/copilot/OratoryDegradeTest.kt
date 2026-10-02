package com.bettertalker.app.data.copilot

import com.bettertalker.app.ui.copilot.shouldDegradeOratoryToChat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Degrade da rota Oratory para o chat normal (F2e). Puro, sem Room/LLM.
 */
class OratoryDegradeTest {

    @Test
    fun shouldDegrade_semS34ComOutline_retornaTrue() {
        // O caso do bug: esboço colado válido, sem anexo S-34.
        assertTrue(shouldDegradeOratoryToChat(hasS34Document = false, hasLinkedOutline = true))
    }

    @Test
    fun shouldDegrade_semS34SemOutline_retornaFalse() {
        // Sem esboço nenhum: mantém a mensagem "Importe o S-34".
        assertFalse(shouldDegradeOratoryToChat(hasS34Document = false, hasLinkedOutline = false))
    }

    @Test
    fun shouldDegrade_comS34_retornaFalse() {
        assertFalse(shouldDegradeOratoryToChat(hasS34Document = true, hasLinkedOutline = true))
        assertFalse(shouldDegradeOratoryToChat(hasS34Document = true, hasLinkedOutline = false))
    }
}
