package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.edit.EditProposalMode
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4 — paridade do marcador de criação nos prompts (Android). */
class SuggestionPromptTest {

    private val marker = "〈sugestão〉"

    @Test
    fun systemPromptTemMarcador() {
        assertTrue(SYSTEM_PROMPT.contains(marker))
    }

    @Test
    fun chatPromptTemMarcador() {
        val p = buildChatPrompt(
            message = "oi",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null,
            blockMinutes = null,
            blockText = "",
        )
        assertTrue(p.contains(marker))
    }

    @Test
    fun editProposalTemMarcador() {
        val p = buildEditProposalPrompt(
            mode = EditProposalMode.INSERT,
            text = "bloco",
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null,
            blockMinutes = null,
            brief = "",
        )
        assertTrue(p.contains(marker))
    }

    @Test
    fun oratoryBaseRulesTemMarcador() {
        assertTrue(OratoryGeneration.BASE_RULES.contains(marker))
    }
}
