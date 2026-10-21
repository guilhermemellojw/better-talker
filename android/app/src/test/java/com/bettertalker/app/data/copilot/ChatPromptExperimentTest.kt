package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptExperimentTest {

    private val emptyPack = ContextPack(emptyList(), emptyList())

    private fun system(
        cardIntent: Boolean = true,
        unifiedPersona: Boolean = false,
    ): String = buildDeepSeekPrompts(
        message = "pergunta",
        history = emptyList(),
        isFirstMessage = true,
        pack = emptyPack,
        blockTitle = null,
        blockMinutes = null,
        blockText = "",
        cardIntent = cardIntent,
        unifiedPersona = unifiedPersona,
    ).system

    @Test
    fun cardIntentOn_mantemRegraDoCard() {
        assertTrue(system(cardIntent = true).contains("<texto-do-card>"))
    }

    @Test
    fun cardIntentOff_removeRegraDoCard() {
        assertFalse(system(cardIntent = false).contains("<texto-do-card>"))
    }

    @Test
    fun personaUnificada_incluiGuiaDeLinguagem() {
        assertTrue(system(unifiedPersona = true).contains(LANGUAGE_PRINCIPLES))
    }
}
