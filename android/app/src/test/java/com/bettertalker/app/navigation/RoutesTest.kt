package com.bettertalker.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navegação global: rotas da barra + construção das rotas parametrizadas. */
class RoutesTest {

    @Test
    fun barraGlobal_nasCincoAbas() {
        assertTrue(shouldShowGlobalBar(Routes.ASSISTANT))
        assertTrue(shouldShowGlobalBar(Routes.LIBRARY))
        assertTrue(shouldShowGlobalBar(Routes.PREPARE))
        assertTrue(shouldShowGlobalBar(Routes.TRAIN_HOME))
        assertTrue(shouldShowGlobalBar(Routes.PRESENT_HOME))
        // Meus esboços, editor, mapa mental e ensaio/palco ao vivo: sem barra.
        assertFalse(shouldShowGlobalBar(Routes.HOME))
        assertFalse(shouldShowGlobalBar(Routes.STAGE))
        assertFalse(shouldShowGlobalBar(Routes.EDITOR))
        assertFalse(shouldShowGlobalBar(Routes.TRIBUNA))
        assertFalse(shouldShowGlobalBar(Routes.PRESENT))
        assertFalse(shouldShowGlobalBar(Routes.SETTINGS))
        assertFalse(shouldShowGlobalBar(Routes.ONBOARDING))
        assertFalse(shouldShowGlobalBar(null))
    }

    @Test
    fun abaAtiva_destacaAsCincoAbas() {
        assertEquals(BottomTab.ASSISTANT, activeTab(Routes.ASSISTANT))
        assertEquals(BottomTab.LIBRARY, activeTab(Routes.LIBRARY))
        assertEquals(BottomTab.PREPARE, activeTab(Routes.PREPARE))
        assertEquals(BottomTab.TRAIN, activeTab(Routes.TRAIN_HOME))
        assertEquals(BottomTab.PRESENT, activeTab(Routes.PRESENT_HOME))
        // Fora das abas.
        assertNull(activeTab(Routes.HOME))
        assertNull(activeTab(Routes.TRIBUNA))
        assertNull(activeTab(Routes.STAGE))
        assertNull(activeTab(Routes.EDITOR))
        assertNull(activeTab(Routes.ONBOARDING))
        assertNull(activeTab(null))
    }

    @Test
    fun assistente_restauraUltimaNotaSomenteSeElaAindaExiste() {
        assertEquals("nota-1", resolveAssistantNoteId("nota-1", setOf("nota-1", "nota-2")))
        assertNull(resolveAssistantNoteId("nota-1", setOf("nota-2")))
        assertNull(resolveAssistantNoteId(null, setOf("nota-1")))
    }

    @Test
    fun intencaoDeMeusEsbocosMudaRotuloEAcao() {
        assertEquals(null, HomeIntent.MANAGE.selectionLabel())
        assertEquals(
            "Escolha o esboço do Assistente",
            HomeIntent.ASSISTANT.selectionLabel(),
        )
        assertEquals(HomeTapAction.OPEN_PREPARE, HomeIntent.MANAGE.tapAction(false))
        assertEquals(HomeTapAction.OPEN_PREPARE, HomeIntent.ASSISTANT.tapAction(true))
        assertEquals(HomeTapAction.SELECT, HomeIntent.ASSISTANT.tapAction(false))
        assertEquals(HomeTapAction.SELECT, HomeIntent.PREPARE.tapAction(false))
        assertEquals(HomeTapAction.SELECT, HomeIntent.TRAIN.tapAction(false))
        assertEquals(HomeTapAction.SELECT, HomeIntent.PRESENT.tapAction(false))
        assertEquals(HomeIntent.PREPARE, homeIntentFromRoute("prepare"))
        assertEquals(HomeIntent.MANAGE, homeIntentFromRoute("nope"))
        assertEquals(HomeIntent.MANAGE, homeIntentFromRoute(null))
    }

    @Test
    fun rotasParametrizadas() {
        assertEquals("stage/note-1", Routes.stage("note-1"))
        assertEquals("tribuna/note-1", Routes.tribuna("note-1"))
        assertEquals("editor/note-1", Routes.editor("note-1"))
        assertEquals("home?intent=manage", Routes.home())
        assertEquals("home?intent=assistant", Routes.home(HomeIntent.ASSISTANT))
        assertEquals("home?intent=prepare", Routes.home(HomeIntent.PREPARE))
        assertEquals("home?intent=train", Routes.home(HomeIntent.TRAIN))
        assertEquals("home?intent=present", Routes.home(HomeIntent.PRESENT))
    }
}
