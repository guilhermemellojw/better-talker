package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun falhaDoLedgerSuprimeLegadoENaoPromoveDecisaoAntiga() = runBlocking {
        val oldDecision = "começar com uma pergunta"
        val result = loadDecisionPromptMemory(
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = false,
            load = { error("ledger indisponível") },
        )
        assertNull(result.block)
        assertFalse(result.available)
        assertTrue(result.suppressLegacyApproach)
        // agreedApproach legado suprimido (simulado pelo contextBlock sem ele) e
        // nenhuma decisão antiga promovida a orientação confirmada.
        val prompts = buildDeepSeekPrompts(
            message = "continue",
            history = emptyList(),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 1",
            blockMinutes = 5,
            blockText = "Texto do ponto.",
            contextBlock = "## SEÇÃO ATUAL\nPonto 1",
            userContextBlock = result.block,
        )
        assertFalse(prompts.user.contains(oldDecision))
        assertFalse(prompts.system.contains(oldDecision))
        assertFalse(prompts.user.contains("## DECISÕES CONFIRMADAS"))
        // Requisição continua operacional.
        assertTrue(prompts.user.isNotBlank())
        assertTrue(prompts.system.isNotBlank())
    }

    @Test
    fun geracaoOratoriaColocaMemoriaDaSecaoResolvidaNoUserEDossieNoSystem() {
        // P4/P1: mesmo com dossiê do foco anterior (s1) no system, a memória
        // canônica da seção resolvida (s2) entra SOMENTE no user (volátil).
        val sectionDecision = DecisionRecord(
            id = "c", noteId = "n1", scope = DecisionScope.SECTION,
            kind = DecisionKind.CONFIRMED, decisionState = DecisionState.ACTIVE,
            sectionLink = SectionLink.VALID, applicationState = ApplicationState.NOT_APPLIED,
            text = "encerrar com uma aplicação prática", source = DecisionSource.EXPLICIT_USER,
            sectionId = "s2", originalSectionId = "s2",
            sectionTitleSnapshot = "Conclusão", sectionRoleSnapshot = com.bettertalker.app.domain.speech.SectionRole.CONCLUSION,
            sectionOrderSnapshot = 2, sectionContentHashSnapshot = "hash-s2",
            originalSectionTitleSnapshot = "Conclusão", originalSectionRoleSnapshot = com.bettertalker.app.domain.speech.SectionRole.CONCLUSION,
            originalSectionOrderSnapshot = 2, originalSectionContentHashSnapshot = "hash-s2",
            createdAt = 10L, updatedAt = 10L,
        )
        val canonical = DecisionPromptMemory.build(
            decisions = listOf(sectionDecision),
            noteId = "n1", focusSectionId = "s2", wholeNote = false,
        )
        val dossierDaSecaoResolvida = "## SEÇÃO ATUAL\nPonto 2 (dossiê da seção resolvida)"
        val prompts = buildDeepSeekPrompts(
            message = "prepare a conclusão",
            history = emptyList(),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Conclusão",
            blockMinutes = 5,
            blockText = "Texto da conclusão.",
            contextBlock = dossierDaSecaoResolvida,
            userContextBlock = canonical.block,
        )
        // Memória da seção resolvida no user (volátil), não no system.
        assertTrue(prompts.user.contains("encerrar com uma aplicação prática"))
        assertTrue(prompts.user.contains("## CONTEXTO DESTE TURNO"))
        assertFalse(prompts.system.contains("encerrar com uma aplicação prática"))
        assertFalse(prompts.system.contains("## DECISÕES CONFIRMADAS"))
        // P5: dossiê da seção resolvida no system; dossiê do foco anterior
        // NÃO é enviado (rejeitado).
        assertTrue(prompts.system.contains("dossiê da seção resolvida"))
        assertFalse(prompts.system.contains("dossiê do foco anterior"))
        assertFalse(prompts.user.contains("dossiê da seção resolvida"))
        // Sem duplicação da memória no user.
        assertEquals(1, Regex("encerrar com uma aplicação prática").findAll(prompts.user).count())
    }
}
