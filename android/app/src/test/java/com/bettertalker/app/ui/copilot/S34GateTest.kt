package com.bettertalker.app.ui.copilot

import com.bettertalker.app.domain.speech.DiscourseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #15a — gate S-34 removido: sem esboço, a geração degrada para o chat
 * normal (nunca recusa) e o aviso suave entra no fim da resposta; com
 * esboço, a geração oratória ancorada segue sem aviso. Puro, sem Room/LLM.
 */
class S34GateTest {

    @Test
    fun semS34_naoRecusa_degradaParaChat() {
        // Antes: postText("Importe o S-34") + return. Agora: answerRemote
        // (seção + corpus + RAG) com o aviso suave no fim.
        assertTrue(shouldDegradeOratoryToChat(hasS34Document = false))
    }

    @Test
    fun comS34_geracaoAncorada_semAviso() {
        assertFalse(shouldDegradeOratoryToChat(hasS34Document = true))
    }

    @Test
    fun avisoSoValeParaNotaTipoS34() {
        assertTrue(needsS34Warning(DiscourseType.S34_DISCOURSE))
        assertFalse(needsS34Warning(DiscourseType.TREASURES_TALK))
        assertFalse(needsS34Warning(DiscourseType.MINISTRY_PART))
        assertFalse(needsS34Warning(DiscourseType.MINISTRY_TALK))
        assertFalse(needsS34Warning(DiscourseType.FREE_DEMO))
        assertFalse(needsS34Warning(DiscourseType.AVULSO))
        assertFalse(needsS34Warning(null))
    }

    @Test
    fun avisoSuave_anexadoNoFimDaResposta() {
        val out = appendNoS34Warning("Resposta com conteúdo gerado.")
        assertTrue(out.startsWith("Resposta com conteúdo gerado."))
        assertTrue(out.endsWith(NO_S34_WARNING))
    }

    @Test
    fun avisoSuave_nuncaDuplica() {
        val once = appendNoS34Warning("Resposta.")
        val twice = appendNoS34Warning(once)
        assertEquals(once, twice)
        assertEquals(1, twice.split(NO_S34_WARNING).size - 1)
    }

    @Test
    fun avisoSuave_respostaVazia_aindaInforma() {
        assertEquals(NO_S34_WARNING, appendNoS34Warning(""))
    }
}
