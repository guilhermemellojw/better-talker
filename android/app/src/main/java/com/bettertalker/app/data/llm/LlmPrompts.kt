package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.buildEditProposalPrompt
import com.bettertalker.app.data.domain.ContextPack

/**
 * Seleção de prompt com paridade ao contrato dos providers
 * remotos: o mesmo [LlmRequest] produz system+user equivalentes; só o motor
 * muda.
 */
internal fun buildProviderPrompts(request: LlmRequest): Pair<String, String> {
    // T1 (modo colar e conversar): prompt minimalista em vez do estruturado.
    request.minimalPaste?.let { pasted ->
        return com.bettertalker.app.data.copilot.buildMinimalPastePrompts(
            pastedText = pasted,
            message = request.message.ifBlank { request.text },
            history = request.history,
        )
    }
    val base: Pair<String, String> = if (request.oratorySpec != null) {
        val user = OratoryGeneration.buildPrompt(
            request.oratorySpec,
            request.message.ifBlank { request.brief },
        )
        "" to user
    } else if (request.responseFormat == ResponseFormat.EDIT_PROPOSAL && request.editMode != null) {
        val user = buildEditProposalPrompt(
            mode = request.editMode,
            text = request.text,
            pack = request.contextPack ?: ContextPack(emptyList(), emptyList()),
            blockTitle = request.blockTitle,
            blockMinutes = request.blockMinutes,
            brief = request.brief,
            legacyPassages = request.contextPassages,
        )
        "" to user
    } else {
        val user = buildChatPrompt(
            message = request.message.ifBlank { request.text },
            history = request.history,
            isFirstMessage = request.isFirstMessage,
            pack = request.contextPack ?: ContextPack(emptyList(), emptyList()),
            blockTitle = request.blockTitle,
            blockMinutes = request.blockMinutes,
            blockText = request.text,
            legacyPassages = request.contextPassages,
            structural = request.structural,
            oratory = request.oratory,
        )
        "" to user
    }
    val block = request.contextBlock?.takeIf { it.isNotBlank() } ?: return base
    return base.first to ("## CONTEXTO DO DOSSIÊ (seção em foco no editor)\n$block\n\n" + base.second)
}
