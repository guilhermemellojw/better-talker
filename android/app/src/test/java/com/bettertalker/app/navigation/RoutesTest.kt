package com.bettertalker.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navegação global: rotas da barra + construção das rotas parametrizadas. */
class RoutesTest {

    @Test
    fun barraGlobal_noAssistenteNaHomeHubsETribuna() {
        assertTrue(shouldShowGlobalBar(Routes.ASSISTANT))
        assertTrue(shouldShowGlobalBar(Routes.HOME))
        assertTrue(shouldShowGlobalBar(Routes.TRAIN_HOME))
        assertTrue(shouldShowGlobalBar(Routes.PRESENT_HOME))
        assertTrue(shouldShowGlobalBar(Routes.TRIBUNA))
        assertFalse(shouldShowGlobalBar(Routes.STAGE))
        assertFalse(shouldShowGlobalBar(Routes.EDITOR))
        assertFalse(shouldShowGlobalBar(Routes.SETTINGS))
        assertFalse(shouldShowGlobalBar(null))
    }

    @Test
    fun abaAtiva_destacaOHubCorreto() {
        assertEquals(BottomTab.ASSISTANT, activeTab(Routes.ASSISTANT))
        assertEquals(BottomTab.HOME, activeTab(Routes.HOME))
        assertEquals(BottomTab.TRAIN, activeTab(Routes.TRAIN_HOME))
        assertEquals(BottomTab.PRESENT, activeTab(Routes.PRESENT_HOME))
        // A Tribuna mostra a barra e destaca "Apresentar".
        assertEquals(BottomTab.PRESENT, activeTab(Routes.TRIBUNA))
        // Ensaio é imersivo (sem barra) e demais telas não têm aba.
        assertNull(activeTab(Routes.STAGE))
        assertNull(activeTab(Routes.EDITOR))
        assertNull(activeTab(null))
    }

    @Test
    fun assistente_restauraUltimaNotaSomenteSeElaAindaExiste() {
        assertEquals("nota-1", resolveAssistantNoteId("nota-1", setOf("nota-1", "nota-2")))
        assertNull(resolveAssistantNoteId("nota-1", setOf("nota-2")))
        assertNull(resolveAssistantNoteId(null, setOf("nota-1")))
    }

    @Test
    fun rotasParametrizadas() {
        assertEquals("stage/note-1", Routes.stage("note-1"))
        assertEquals("tribuna/note-1", Routes.tribuna("note-1"))
        assertEquals("editor/note-1", Routes.editor("note-1"))
        assertEquals("picker/train", Routes.picker("train"))
        assertEquals("picker/present", Routes.picker("present"))
    }
}
