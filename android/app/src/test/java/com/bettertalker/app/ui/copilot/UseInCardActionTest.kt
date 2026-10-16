package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #21-fix — o botão "Usar no card" aplica SÓ o texto do bloco; o pipeline de
 * insert ([cleanForInsert]) limpa marcadores e a dica. (A ação em si —
 * VM/Android — é validada no device.)
 */
class UseInCardActionTest {

    @Test
    fun textoDoBlocoEhLimpoNoInsert() {
        assertEquals("X", cleanForInsert("〈sugestão〉X〈/sugestão〉"))
    }

    @Test
    fun dicaRemovidaNoInsert() {
        val texto = "Discurso.\n\n" +
            "_Se estiver de acordo, diga \"insira isso\" para eu colocar no card._"
        assertEquals("Discurso.", cleanForInsert(texto))
    }
}
