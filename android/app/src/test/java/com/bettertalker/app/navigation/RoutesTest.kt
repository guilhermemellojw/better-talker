package com.bettertalker.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navegação global: rotas da barra + construção das rotas parametrizadas. */
class RoutesTest {

    @Test
    fun barraGlobal_soNaHomeETribuna() {
        assertTrue(shouldShowGlobalBar(Routes.HOME))
        assertTrue(shouldShowGlobalBar(Routes.TRIBUNA))
        assertFalse(shouldShowGlobalBar(Routes.STAGE))
        assertFalse(shouldShowGlobalBar(Routes.EDITOR))
        assertFalse(shouldShowGlobalBar(Routes.SETTINGS))
        assertFalse(shouldShowGlobalBar(null))
    }

    @Test
    fun rotasParametrizadas() {
        assertEquals("stage/note-1", Routes.stage("note-1"))
        assertEquals("tribuna/note-1", Routes.tribuna("note-1"))
        assertEquals("editor/note-1", Routes.editor("note-1"))
    }
}
