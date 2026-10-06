package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/** T4 (Mini Discurso): limpeza de tags e confirmação do insert no editor. */
class InsertTextTest {

    @Test
    fun cleanForInsert_removeParDeTagsEPreservaTexto() {
        assertEquals(
            "Veja: uma metáfora do rio fim.",
            cleanForInsert("Veja: 〈sugestão〉uma metáfora do rio〈/sugestão〉 fim.")
        )
    }

    @Test
    fun cleanForInsert_tagSoltaSomeEApara() {
        assertEquals("abre sem fechar", cleanForInsert("  abre 〈sugestão〉sem fechar  "))
    }

    @Test
    fun cleanForInsert_semTagApenasApara() {
        assertEquals("texto normal", cleanForInsert("  texto normal \n"))
    }

    @Test
    fun insertConfirmation_comTopoMencionaOTopico() {
        assertEquals("Inserido no tópico “Abraão”.", insertConfirmation("Abraão"))
    }

    @Test
    fun insertConfirmation_semTopoFalaDaNota() {
        assertEquals("Inserido na nota.", insertConfirmation(null))
    }
}
