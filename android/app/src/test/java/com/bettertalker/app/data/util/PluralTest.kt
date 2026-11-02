package com.bettertalker.app.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PluralTest {

    @Test
    fun umUsaSingular() {
        assertEquals("nota", plural(1, "nota", "notas"))
    }

    @Test
    fun zeroDoisEMuitosUsamPlural() {
        assertEquals("notas", plural(0, "nota", "notas"))
        assertEquals("notas", plural(2, "nota", "notas"))
        assertEquals("notas", plural(120, "nota", "notas"))
    }

    @Test
    fun disponivel() {
        assertEquals("disponível", plural(1, "disponível", "disponíveis"))
        assertEquals("disponíveis", plural(3, "disponível", "disponíveis"))
    }
}
