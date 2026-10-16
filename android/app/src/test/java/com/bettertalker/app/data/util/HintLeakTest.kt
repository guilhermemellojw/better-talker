package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** #21-fix — a dica "insira isso" não vaza para o card. Puro. */
class HintLeakTest {

    private val dica = "_Se estiver de acordo, diga \"insira isso\" para eu colocar no card._"

    @Test
    fun dicaNoFimEhRemovida() {
        val texto = "Conteúdo do discurso aqui.\n\n$dica"
        assertEquals("Conteúdo do discurso aqui.", stripMetaPreamble(texto))
    }

    @Test
    fun dicaNoMeioEhPreservada() {
        val texto = "Primeiro parágrafo.\n\n" +
            "Ele disse que se estiver de acordo, podemos seguir.\n\nÚltimo."
        assertEquals(texto, stripMetaPreamble(texto))
    }
}
