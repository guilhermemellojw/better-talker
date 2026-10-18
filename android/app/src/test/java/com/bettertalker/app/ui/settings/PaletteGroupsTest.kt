package com.bettertalker.app.ui.settings

import com.bettertalker.app.ui.theme.PaletteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grupos de paletas da tela de Aparência: cobertura completa e sem repetição. */
class PaletteGroupsTest {

    @Test
    fun grupos_cobremTodasAsPaletasExatamenteUmaVez() {
        val ids = PALETTE_GROUPS.flatMap { it.second }
        assertEquals(PaletteId.entries.size, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(PaletteId.entries.toSet(), ids.toSet())
    }

    @Test
    fun grupos_temNomeESaoNaoVazios() {
        PALETTE_GROUPS.forEach { (name, ids) ->
            assertTrue(name.isNotBlank())
            assertTrue(ids.isNotEmpty())
        }
    }
}
