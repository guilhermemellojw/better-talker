package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #10c: escopo do chat (Tópico ↔ Discurso inteiro). O VM é fino demais para
 * a suíte JVM (sem Robolectric); as regras puras vivem aqui.
 */
class ChatScopeToggleTest {

    @Test
    fun toggleWholeToSection_voltaAoUltimoTopico() {
        assertEquals("s1", scopeRestoreTarget("s1", listOf("s1", "s2")))
    }

    @Test
    fun semUltimoFoco_usaPrimeiraSecao() {
        assertEquals("s1", scopeRestoreTarget(null, listOf("s1", "s2")))
    }

    @Test
    fun ultimoFocoRemovido_usaPrimeiraSecao() {
        assertEquals("s2", scopeRestoreTarget("sX", listOf("s2")))
    }

    @Test
    fun semSecoes_ficaEmWhole() {
        assertNull(scopeRestoreTarget(null, emptyList()))
        assertNull(scopeRestoreTarget("s1", emptyList()))
    }

    @Test
    fun escritaEmWhole_avisa() {
        assertEquals(
            SCOPE_WHOLE_INSERT_NOTICE,
            scopeBlockedNotice(ScopeMode.WHOLE, insert = true, nav = false),
        )
    }

    @Test
    fun escritaEmSection_naoBloqueia() {
        assertNull(scopeBlockedNotice(ScopeMode.SECTION, insert = true, nav = false))
    }

    @Test
    fun navegacaoEmWhole_avisa() {
        assertEquals(
            SCOPE_WHOLE_NAV_NOTICE,
            scopeBlockedNotice(ScopeMode.WHOLE, insert = false, nav = true),
        )
    }

    @Test
    fun modoBEmWhole_naoBloqueia() {
        // "insira tudo"/materializar não é escrita em card: roda normal.
        assertNull(scopeBlockedNotice(ScopeMode.WHOLE, insert = false, nav = false))
    }

    @Test
    fun textosLiteraisDosAvisos() {
        assertEquals("Mude o escopo para Tópico para inserir no card.", SCOPE_WHOLE_INSERT_NOTICE)
        assertEquals("Mude o escopo para Tópico para navegar entre seções.", SCOPE_WHOLE_NAV_NOTICE)
    }
}
