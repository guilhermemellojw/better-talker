package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.0a/foco — entrada do chat:
 * push one-shot > seção ativa do editor > discurso inteiro.
 * Nunca reaproveita a última seção persistida.
 */
class ChatFocusPolicyTest {

    private val guide = setOf("sec-intro", "sec-ponto", "sec-conclusao")

    @Test
    fun pushDoFabVenceEMantemAFoco() {
        val focus = resolveChatEntryFocus(
            hasPendingPush = true,
            activeSectionId = "sec-conclusao",
            guideSectionIds = guide,
            openedFromEditor = true,
        )
        assertTrue(focus.keepPushed)
        assertFalse(focus.whole)
    }

    @Test
    fun fabNaConclusaoSemPushAbreNaConclusao() {
        val focus = resolveChatEntryFocus(
            hasPendingPush = false,
            activeSectionId = "sec-conclusao",
            guideSectionIds = guide,
            openedFromEditor = true,
        )
        assertEquals("sec-conclusao", focus.sectionId)
        assertFalse(focus.whole)
    }

    @Test
    fun menuDoAssistenteSemPushAbreNoDiscursoInteiro() {
        val focus = resolveChatEntryFocus(
            hasPendingPush = false,
            activeSectionId = "sec-conclusao",
            guideSectionIds = guide,
            openedFromEditor = false,
        )
        assertNull(focus.sectionId)
        assertTrue(focus.whole)
    }

    @Test
    fun trocarDeNotaComSecaoInexistenteAbreNoInteiro() {
        val focus = resolveChatEntryFocus(
            hasPendingPush = false,
            activeSectionId = "sec-da-nota-anterior",
            guideSectionIds = guide,
            openedFromEditor = true,
        )
        assertNull(focus.sectionId)
        assertTrue(focus.whole)
    }

    @Test
    fun semSecaoAtivaAbreNoInteiro() {
        val focus = resolveChatEntryFocus(
            hasPendingPush = false,
            activeSectionId = null,
            guideSectionIds = guide,
            openedFromEditor = true,
        )
        assertNull(focus.sectionId)
        assertTrue(focus.whole)
    }

    @Test
    fun mesmaSessaoNaoReaplicaNaRotacao() {
        assertFalse(isNewChatEntrySession("chat|n1", "chat|n1"))
        assertTrue(isNewChatEntrySession("chat|n1", "chat|n2"))
        assertTrue(isNewChatEntrySession(null, "chat|n1"))
    }
}
