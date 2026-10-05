package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.util.ChatIntent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 (Bug #7) — refs determinísticas mesmo com provider ativo. */
class RefsCommandTest {

    @Test
    fun outlineRefsECheckRefsSaoComandosDeReferencia() {
        assertTrue(isRefsCommand(ChatIntent.Intent.OutlineRefs))
        assertTrue(isRefsCommand(ChatIntent.Intent.CheckRefs))
    }

    @Test
    fun outrosIntentsNaoSao() {
        assertFalse(isRefsCommand(ChatIntent.Intent.Help))
        assertFalse(isRefsCommand(ChatIntent.Intent.Bases))
    }
}
