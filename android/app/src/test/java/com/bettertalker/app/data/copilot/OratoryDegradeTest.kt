package com.bettertalker.app.data.copilot

import com.bettertalker.app.ui.copilot.shouldDegradeOratoryToChat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Degrade da rota Oratory para o chat normal (F2e + #15a). Puro, sem Room/LLM.
 */
class OratoryDegradeTest {

    @Test
    fun shouldDegrade_semS34ComOutline_retornaTrue() {
        // O caso do bug F2e: esboço colado válido, sem anexo S-34.
        assertTrue(shouldDegradeOratoryToChat(hasS34Document = false))
    }

    @Test
    fun shouldDegrade_semS34SemOutline_tambemDegrada() {
        // #15a: o gate duro foi removido — sem esboço nenhum TAMBÉM degrada
        // (antes mantinha a mensagem "Importe o S-34"; agora responde pelo
        // chat normal, com aviso suave no fim).
        assertTrue(shouldDegradeOratoryToChat(hasS34Document = false))
    }

    @Test
    fun shouldDegrade_comS34_retornaFalse() {
        assertFalse(shouldDegradeOratoryToChat(hasS34Document = true))
    }
}
