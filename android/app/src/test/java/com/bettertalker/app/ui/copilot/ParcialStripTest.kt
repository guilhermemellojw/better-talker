package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.util.stripInternalTags
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #17-fix — parcial do streaming: marcadores internos fora, conteúdo fica.
 * Política: marcador órfão (sem par) é removido como tag, nunca corta o
 * texto — o stream não perde conteúdo no meio.
 */
class ParcialStripTest {

    @Test
    fun parCompletoViraConteudo() {
        assertEquals("X", stripInternalTags("〈sugestão〉X〈/sugestão〉"))
    }

    @Test
    fun tagIncompletaNaoCortaOConteudo() {
        assertEquals("X", stripInternalTags("〈sugestão〉X"))
    }
}
