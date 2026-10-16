package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.llm.DeepSeekProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** #17-fix — chip Rápido/Profundo: default, alternância e esforço por rota. Puro. */
class ThinkingModeTest {

    @Test
    fun defaultEhRapido() {
        assertEquals(ThinkingMode.FAST, ThinkingMode.DEFAULT)
    }

    @Test
    fun toggleAlterna() {
        assertEquals(ThinkingMode.DEEP, ThinkingMode.FAST.toggled())
        assertEquals(ThinkingMode.FAST, ThinkingMode.DEEP.toggled())
    }

    @Test
    fun rapidoViraLow() {
        assertEquals("low", thinkingEffortFor(ThinkingMode.FAST))
    }

    @Test
    fun profundoViraHigh() {
        assertEquals("high", thinkingEffortFor(ThinkingMode.DEEP))
    }

    @Test
    fun estruturadaForcaNoneMesmoComProfundo() {
        assertEquals(
            DeepSeekProvider.STRUCTURED_REASONING_EFFORT,
            DeepSeekProvider.reasoningEffortFor(
                structured = true, minimalPaste = false, configured = "high",
            ),
        )
    }

    @Test
    fun modoColarForcaNoneMesmoComProfundo() {
        assertEquals(
            DeepSeekProvider.MINIMAL_REASONING_EFFORT,
            DeepSeekProvider.reasoningEffortFor(
                structured = false, minimalPaste = true, configured = "high",
            ),
        )
    }
}
