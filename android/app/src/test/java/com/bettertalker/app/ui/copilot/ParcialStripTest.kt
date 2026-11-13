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

    // #21-fix: a tag do bloco aplicável também fica oculta no parcial.

    @Test
    fun tagDoCardSomeNoParcial() {
        assertEquals(
            "abc def ghi",
            stripInternalTags("abc <texto-do-card>def</texto-do-card> ghi"),
        )
    }

    @Test
    fun tagDoCardIncompletaNaoCortaOConteudo() {
        assertEquals("abc def", stripInternalTags("abc <texto-do-card>def"))
    }

    @Test
    fun todosOsPrefixosDaTagDoCardNoFimSomem() {
        val full = "<texto-do-card>"
        for (len in 1..full.length) {
            val prefix = full.substring(0, len)
            assertEquals("abc", stripInternalTags("abc $prefix"))
        }
        val closing = "</texto-do-card>"
        for (len in 1..closing.length) {
            val prefix = closing.substring(0, len)
            assertEquals("abc", stripInternalTags("abc $prefix"))
        }
    }

    @Test
    fun todosOsPrefixosDaTagCjkNoFimSomem() {
        val full = "〈sugestão〉"
        for (len in 1..full.length) {
            val prefix = full.substring(0, len)
            assertEquals("abc", stripInternalTags("abc $prefix"))
        }
    }

    @Test
    fun prefixoNoMeioNaoEhRemovido() {
        assertEquals(
            "abc <texto-do- def",
            stripInternalTags("abc <texto-do- def"),
        )
    }
}
