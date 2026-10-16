package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #18-fix — dicas de meta (preâmbulo/pós-escrito) só no início de frase:
 * sem falso-positivo por substring. Puro, sem Android.
 */
class StripMetaPreambleTest {

    @Test
    fun declarouNaoEhMeta() {
        val text = "Declarou que a organização segue a ordem divina.\n\nConteúdo principal."
        assertEquals(text, stripMetaPreamble(text))
    }

    @Test
    fun claroNoInicioEhMeta() {
        assertEquals(
            "Conteúdo principal.",
            stripMetaPreamble("Claro! Aqui está o texto:\n\nConteúdo principal."),
        )
    }

    @Test
    fun argumentoQueSegueNaoEhMeta() {
        val text = "O argumento que segue é forte e claro.\n\nConteúdo principal."
        assertEquals(text, stripMetaPreamble(text))
    }

    @Test
    fun posEscritoNoFimEhMeta() {
        assertEquals(
            "Conteúdo principal.",
            stripMetaPreamble("Conteúdo principal.\n\nEspero ter ajudado."),
        )
    }

    @Test
    fun claramenteNaoEhMeta() {
        val text = "Claramente, o texto diz o contrário.\n\nConteúdo principal."
        assertEquals(text, stripMetaPreamble(text))
    }
}
