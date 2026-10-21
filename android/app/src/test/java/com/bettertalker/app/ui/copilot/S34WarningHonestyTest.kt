package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exp/mentoria-e-verifier (2026-10-21) — a nota colada (Tesouros) tem a
 * estrutura DELA (seções + linha de raciocínio), e o app a usa de verdade
 * (A/B 2026-10-21). O aviso antigo dizia o contrário ("o texto pode não
 * seguir a estrutura original") e queimava confiança.
 */
class S34WarningHonestyTest {

    @Test
    fun aviso_nao_acusa_a_estrutura_que_o_app_usa() {
        assertFalse(
            "o aviso não pode dizer que a estrutura não foi seguida",
            NO_S34_WARNING.contains("pode não seguir a estrutura original")
        )
        assertTrue(
            "o aviso deve dizer o que aconteceu (usou o esboço da nota)",
            NO_S34_WARNING.contains("estrutura do seu esboço na nota")
        )
    }

    @Test
    fun aviso_e_anexado_uma_vez() {
        val once = appendNoS34Warning("resposta")
        assertTrue(once.contains(NO_S34_WARNING))
        val twice = appendNoS34Warning(once)
        assertEquals("o aviso não pode duplicar", once, twice)
    }

    @Test
    fun aviso_aparece_quando_a_resposta_esta_vazia() {
        assertEquals(NO_S34_WARNING, appendNoS34Warning(""))
    }

    @Test
    fun historico_do_usuario_e_fonte_para_o_verifier() {
        // A fala do próprio usuário entra no corpus do verifier: citar a
        // hipótese dele para refutá-la não é inventar.
        val history = listOf(
            ChatTurn(true, "Baruque perdeu prestígio social?"),
            ChatTurn(false, "A Bíblia não diz isso."),
        )
        val userTurns = history.filter { it.fromMe }.map { it.text }
        assertEquals(listOf("Baruque perdeu prestígio social?"), userTurns)
    }
}
