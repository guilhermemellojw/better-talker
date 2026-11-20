package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.2B0 — o bloco canônico chega ao payload real e não contamina o system. */
class DecisionPromptPayloadTest {

    private fun decision(
        id: String,
        text: String,
        state: DecisionState = DecisionState.ACTIVE,
        updatedAt: Long = 10L,
    ) = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.SECTION,
        kind = DecisionKind.CONFIRMED,
        decisionState = state,
        sectionLink = SectionLink.VALID,
        applicationState = ApplicationState.NOT_APPLIED,
        text = text,
        source = DecisionSource.EXPLICIT_USER,
        sectionId = "s1",
        originalSectionId = "s1",
        sectionTitleSnapshot = "Ponto 1",
        sectionRoleSnapshot = com.bettertalker.app.domain.speech.SectionRole.BODY,
        sectionOrderSnapshot = 1,
        sectionContentHashSnapshot = "hash-s1",
        originalSectionTitleSnapshot = "Ponto 1",
        originalSectionRoleSnapshot = com.bettertalker.app.domain.speech.SectionRole.BODY,
        originalSectionOrderSnapshot = 1,
        originalSectionContentHashSnapshot = "hash-s1",
        createdAt = 10L,
        updatedAt = updatedAt,
    )

    private fun memory() = DecisionPromptMemory.build(
        decisions = listOf(
            decision("a", "começar com uma pergunta", state = DecisionState.SUPERSEDED),
            decision("b", "começar com uma ilustração", updatedAt = 20L),
        ),
        noteId = "n1",
        focusSectionId = "s1",
        wholeNote = false,
    )

    @Test
    fun promptRealColocaBNoUserESystemNaoRecebeMemoria() {
        val canonical = memory()
        assertNotNull(canonical.block)
        val prompts = buildDeepSeekPrompts(
            message = "continue",
            history = emptyList(),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 1",
            blockMinutes = 5,
            blockText = "Texto do ponto.",
            contextBlock = "## SEÇÃO ATUAL\nPonto 1",
            userContextBlock = canonical.block,
        )

        assertTrue(prompts.user.contains("começar com uma ilustração"))
        assertTrue(prompts.user.contains("## DECISÕES CONFIRMADAS PELO USUÁRIO"))
        assertFalse(prompts.user.contains("começar com uma pergunta"))
        assertFalse(prompts.system.contains("começar com uma pergunta"))
        assertFalse(prompts.system.contains("começar com uma ilustração"))
        assertFalse(prompts.system.contains("## DECISÕES CONFIRMADAS"))
    }

    @Test
    fun historicoComDecisaoAntigaNaoEhPromovidoAMemoria() {
        val canonical = memory()
        val history = listOf(
            ChatTurn(true, "Vamos começar com uma pergunta."),
            ChatTurn(false, "Certo, sigo por essa linha."),
        )
        val prompts = buildDeepSeekPrompts(
            message = "continue",
            history = history,
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 1",
            blockMinutes = 5,
            blockText = "Texto do ponto.",
            userContextBlock = canonical.block,
        )

        // O histórico continua literal, mas não vira "decisão" do prompt.
        assertTrue(prompts.user.contains("Vamos começar com uma pergunta."))
        assertTrue(prompts.user.contains("começar com uma ilustração"))
        assertFalse(prompts.user.contains("## MEMÓRIA DA CONSTRUÇÃO"))
        assertFalse(prompts.user.contains("- Decisão: Vamos começar com uma pergunta"))
        assertFalse(prompts.user.contains("Próximo passo:"))
    }

    @Test
    fun modoFonteNaoRecebeMemoriaDeDecisoes() {
        val canonical = memory()
        val prompts = buildDeepSeekPrompts(
            message = "o que diz João 3:16?",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null,
            blockMinutes = null,
            blockText = "",
            userContextBlock = canonical.block,
            sourceQuestionMode = true,
            sourceQuestionBlock = "## FONTE\nJoão 3:16 — texto verificado",
        )

        assertTrue(prompts.user.contains("João 3:16"))
        assertFalse(prompts.user.contains("começar com uma ilustração"))
        assertFalse(prompts.system.contains("começar com uma ilustração"))
        assertFalse(prompts.user.contains("## DECISÕES CONFIRMADAS"))
    }

    @Test
    fun modoColarMantemMemoriaNoConteudoDoUsuario() {
        val canonical = DecisionPromptMemory.build(
            decisions = listOf(decision("b", "começar com uma ilustração")),
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = false,
            maxChars = com.bettertalker.app.data.copilot.MINIMAL_MEMORY_MAX_CHARS,
        )
        val prefix = minimalMemoryPrefix(canonical.block)
        val (system, user) = buildMinimalPastePrompts(
            pastedText = "Esboço colado.",
            message = prefix + "continue",
            history = emptyList(),
        )

        assertFalse(system.contains("começar com uma ilustração"))
        assertFalse(system.contains("## DECISÕES CONFIRMADAS"))
        assertTrue(user.contains("## DECISÕES CONFIRMADAS"))
        assertTrue(user.contains("começar com uma ilustração"))
    }
}
