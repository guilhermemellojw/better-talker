package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/** #17-fix — rótulo do chip (a renderização Compose em si é validada no device). */
class ChatPromptThinkingChipTest {

    @Test
    fun rapidoMostraRotuloRapido() {
        assertEquals("⚡ Rápido", thinkingChipLabel(ThinkingMode.FAST))
    }

    @Test
    fun profundoMostraRotuloProfundo() {
        assertEquals("🧠 Profundo", thinkingChipLabel(ThinkingMode.DEEP))
    }
}
