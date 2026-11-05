package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 3.4: foco do chat (Tópico ↔ Discurso inteiro). O VM é fino demais para a
 * suíte JVM (sem Robolectric); as regras puras vivem aqui.
 */
class ChatFocusToggleTest {

    @Test
    fun toggleWholeToSection_voltaAoUltimoTopico() {
        assertEquals("s1", focusRestoreTarget("s1", listOf("s1", "s2")))
    }

    @Test
    fun semUltimoFoco_usaPrimeiraSecao() {
        assertEquals("s1", focusRestoreTarget(null, listOf("s1", "s2")))
    }

    @Test
    fun ultimoFocoRemovido_usaPrimeiraSecao() {
        assertEquals("s2", focusRestoreTarget("sX", listOf("s2")))
    }

    @Test
    fun semSecoes_ficaEmWhole() {
        assertNull(focusRestoreTarget(null, emptyList()))
        assertNull(focusRestoreTarget("s1", emptyList()))
    }

    @Test
    fun escritaEmWhole_avisa() {
        assertEquals(
            FOCUS_WHOLE_INSERT_NOTICE,
            focusBlockedNotice(FocusMode.WHOLE, insert = true, nav = false),
        )
    }

    @Test
    fun escritaEmSection_naoBloqueia() {
        assertNull(focusBlockedNotice(FocusMode.SECTION, insert = true, nav = false))
    }

    @Test
    fun navegacaoEmWhole_avisa() {
        assertEquals(
            FOCUS_WHOLE_NAV_NOTICE,
            focusBlockedNotice(FocusMode.WHOLE, insert = false, nav = true),
        )
    }

    @Test
    fun modoBEmWhole_naoBloqueia() {
        // "insira tudo"/materializar não é escrita em card: roda normal.
        assertNull(focusBlockedNotice(FocusMode.WHOLE, insert = false, nav = false))
    }

    @Test
    fun textosLiteraisDosAvisos() {
        assertEquals("Mude o foco para Tópico para inserir no card.", FOCUS_WHOLE_INSERT_NOTICE)
        assertEquals("Mude o foco para Tópico para navegar entre seções.", FOCUS_WHOLE_NAV_NOTICE)
    }
}
