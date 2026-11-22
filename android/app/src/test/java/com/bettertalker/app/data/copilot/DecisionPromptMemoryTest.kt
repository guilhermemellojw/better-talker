package com.bettertalker.app.data.copilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 1C.2.2B0 — contrato puro da memória canônica do Mentor. */
class DecisionPromptMemoryTest {

    private fun sectionDecision(
        id: String = "d1",
        text: String = "começar com uma ilustração",
        noteId: String = "n1",
        sectionId: String = "s1",
        title: String = "Ponto 1",
        order: Int = 1,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        link: SectionLink = SectionLink.VALID,
        candidate: String? = null,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
        supersedesId: String? = null,
    ) = DecisionRecord(
        id = id,
        noteId = noteId,
        scope = DecisionScope.SECTION,
        kind = kind,
        decisionState = state,
        sectionLink = link,
        applicationState = application,
        text = text,
        source = DecisionSource.EXPLICIT_USER,
        sectionId = sectionId,
        originalSectionId = sectionId,
        candidateSectionId = candidate,
        sectionTitleSnapshot = title,
        sectionRoleSnapshot = com.bettertalker.app.domain.speech.SectionRole.BODY,
        sectionOrderSnapshot = order,
        sectionContentHashSnapshot = "hash-$sectionId",
        originalSectionTitleSnapshot = title,
        originalSectionRoleSnapshot = com.bettertalker.app.domain.speech.SectionRole.BODY,
        originalSectionOrderSnapshot = order,
        originalSectionContentHashSnapshot = "hash-$sectionId",
        supersedesId = supersedesId,
        appliedAt = if (application == ApplicationState.NOT_APPLIED) null else 11L,
        appliedHash = if (application == ApplicationState.NOT_APPLIED) null else "hash-$sectionId",
        createdAt = createdAt,
        updatedAt = updatedAt,
        removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
    )

    private fun noteDecision(
        id: String = "g1",
        text: String = "tom pastoral em toda a nota",
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        createdAt: Long = 10L,
    ) = DecisionRecord(
        id = id,
        noteId = "n1",
        scope = DecisionScope.NOTE,
        kind = kind,
        decisionState = state,
        sectionLink = SectionLink.NOT_APPLICABLE,
        applicationState = application,
        text = text,
        source = DecisionSource.EXPLICIT_USER,
        createdAt = createdAt,
        updatedAt = createdAt,
        removedAt = if (state == DecisionState.REMOVED) createdAt else null,
    )

    private fun build(
        vararg decisions: DecisionRecord,
        focus: String? = "s1",
        whole: Boolean = false,
        maxChars: Int = DecisionPromptMemory.MAX_CHAT_CHARS,
    ) = DecisionPromptMemory.build(
        decisions = decisions.toList(),
        noteId = "n1",
        focusSectionId = focus,
        wholeNote = whole,
        maxChars = maxChars,
    )

    @Test
    fun ledgerVazioNaoProduzBlocoNemSuprimeLegado() {
        val result = build()
        assertNull(result.block)
        assertTrue(result.available)
        assertFalse(result.hasLedgerRecords)
        assertFalse(result.suppressLegacyApproach)
        assertEquals(0, result.includedCount)
    }

    @Test
    fun supersededNaoEntraMasSuprimeLegado() {
        val result = build(
            sectionDecision(
                id = "a",
                text = "começar com pergunta",
                state = DecisionState.SUPERSEDED,
                updatedAt = 20L,
            ),
            sectionDecision(
                id = "b",
                text = "começar com ilustração",
                createdAt = 30L,
                updatedAt = 30L,
            ),
        )
        assertTrue(result.hasLedgerRecords)
        assertTrue(result.suppressLegacyApproach)
        assertTrue(result.block!!.contains("começar com ilustração"))
        assertFalse(result.block.contains("começar com pergunta"))
        assertEquals(1, result.includedCount)
    }

    @Test
    fun somenteRemovidaNaoProduzBlocoMasSuprimeLegado() {
        val result = build(
            sectionDecision(state = DecisionState.REMOVED, updatedAt = 20L),
        )
        assertNull(result.block)
        assertTrue(result.hasLedgerRecords)
        assertTrue(result.suppressLegacyApproach)
        assertEquals(0, result.includedCount)
    }

    @Test
    fun orfaaEPendenteFicamForaDoPrompt() {
        val result = build(
            sectionDecision(id = "orphan", text = "órfã", link = SectionLink.ORPHANED),
            sectionDecision(
                id = "pending",
                text = "pendente",
                link = SectionLink.PENDING_RECONFIRMATION,
                candidate = "s2",
            ),
            sectionDecision(id = "ok", text = "confirmada", createdAt = 40L),
        )
        assertTrue(result.block!!.contains("confirmada"))
        assertFalse(result.block.contains("órfã"))
        assertFalse(result.block.contains("pendente"))
    }

    @Test
    fun naturezasNaoConfirmadasFicamFora() {
        val result = build(
            sectionDecision(id = "pref", text = "preferência", kind = DecisionKind.PREFERENCE),
            sectionDecision(id = "rej", text = "rejeitada", kind = DecisionKind.REJECTED),
            sectionDecision(id = "open", text = "aberta", kind = DecisionKind.OPEN),
        )
        assertNull(result.block)
        assertTrue(result.suppressLegacyApproach)
    }

    @Test
    fun focoIncluiNotaESecaoExataSemVazarOutraSecao() {
        val result = build(
            noteDecision(),
            sectionDecision(id = "s1d", text = "decisão do foco", sectionId = "s1"),
            sectionDecision(
                id = "s2d",
                text = "decisão de outra seção",
                sectionId = "s2",
                title = "Ponto 2",
                order = 2,
            ),
        )
        assertTrue(result.block!!.contains("tom pastoral em toda a nota"))
        assertTrue(result.block.contains("decisão do foco"))
        assertFalse(result.block.contains("decisão de outra seção"))
        assertEquals(1, result.noteCount)
        assertEquals(1, result.sectionCount)
    }

    @Test
    fun semFocoIncluiSomenteDecisoesDaNota() {
        val result = build(
            noteDecision(),
            sectionDecision(id = "s1d", text = "decisão da seção", sectionId = "s1"),
            focus = null,
        )
        assertTrue(result.block!!.contains("tom pastoral em toda a nota"))
        assertFalse(result.block.contains("decisão da seção"))
        assertEquals(1, result.noteCount)
        assertEquals(0, result.sectionCount)
    }

    @Test
    fun visaoDaNotaInteiraRotulaSecoesDeOrigem() {
        val result = build(
            noteDecision(),
            sectionDecision(id = "s1d", text = "decisão A", sectionId = "s1", title = "Ponto 1"),
            sectionDecision(
                id = "s2d",
                text = "decisão B",
                sectionId = "s2",
                title = "Ponto 2",
                order = 2,
            ),
            focus = null,
            whole = true,
        )
        val block = result.block!!
        assertTrue(block.contains("[Seção: Ponto 1]"))
        assertTrue(block.contains("[Seção: Ponto 2]"))
        assertTrue(block.contains("decisão A"))
        assertTrue(block.contains("decisão B"))
        assertFalse(block.contains("[Seção em foco:"))
    }

    @Test
    fun outraNotaNuncaEntraNemSuprime() {
        val result = DecisionPromptMemory.build(
            decisions = listOf(
                sectionDecision(id = "other", noteId = "n2", text = "de outra nota"),
            ),
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = false,
        )
        assertNull(result.block)
        assertFalse(result.hasLedgerRecords)
        assertFalse(result.suppressLegacyApproach)
    }

    @Test
    fun staleEntraComAvisoExplicitoDeAplicacaoDesatualizada() {
        val result = build(
            sectionDecision(text = "usar João 3:16", application = ApplicationState.STALE),
        )
        assertTrue(result.block!!.contains("usar João 3:16"))
        assertTrue(result.block.contains("aplicação desatualizada"))
        assertEquals(1, result.staleCount)
    }

    @Test
    fun naoAplicadaNaoEhApresentadaComoAplicada() {
        val result = build(sectionDecision(application = ApplicationState.NOT_APPLIED))
        val block = result.block!!
        assertTrue(block.contains("ainda não aplicada ao texto."))
        assertFalse(block.contains("1. aplicada ao texto."))
    }

    @Test
    fun aplicadaInformaAplicacaoSemAlterarElegibilidade() {
        val result = build(sectionDecision(application = ApplicationState.APPLIED))
        assertTrue(result.block!!.contains("aplicada ao texto."))
        assertEquals(1, result.includedCount)
    }

    @Test
    fun ordenacaoDeterministicaIndependeDaEntrada() {
        val a = sectionDecision(id = "a", text = "primeira", sectionId = "s1", order = 1)
        val b = sectionDecision(id = "b", text = "segunda", sectionId = "s2", order = 2)
        val c = noteDecision(id = "c", text = "global")
        val first = build(a, b, c)
        val second = build(c, b, a)
        assertEquals(first.block, second.block)
        assertTrue(first.block!!.indexOf("global") < first.block.indexOf("primeira"))
    }

    @Test
    fun dedupeSeguroMantemRegistroMaisRecente() {
        val result = build(
            sectionDecision(id = "velha", text = "mesma decisão", updatedAt = 10L),
            sectionDecision(id = "nova", text = "mesma decisão", updatedAt = 20L),
        )
        assertEquals(1, result.includedCount)
        assertEquals(1, Regex("mesma decisão").findAll(result.block!!).count())
    }

    @Test
    fun mesmoTextoEmSecoesDiferentesNaoEhDeduplicado() {
        val result = build(
            sectionDecision(id = "a", text = "mesma frase", sectionId = "s1", title = "Ponto 1"),
            sectionDecision(
                id = "b",
                text = "mesma frase",
                sectionId = "s2",
                title = "Ponto 2",
                order = 2,
            ),
            focus = null,
            whole = true,
        )
        assertEquals(2, result.includedCount)
        assertEquals(2, Regex("mesma frase").findAll(result.block!!).count())
    }

    @Test
    fun orcamentoNaoTruncaDecisaoPelaMetade() {
        val long = "palavra ".repeat(30).trim()
        val decisions = (1..8).map { i ->
            sectionDecision(
                id = "d$i",
                text = "$long $i",
                sectionId = "s$i",
                title = "Ponto $i",
                order = i,
                createdAt = i.toLong(),
            )
        }
        val result = DecisionPromptMemory.build(
            decisions = decisions,
            noteId = "n1",
            focusSectionId = null,
            wholeNote = true,
            maxChars = 900,
        )
        val block = result.block!!
        assertTrue(block.length <= 900)
        assertTrue(result.includedCount in 1 until decisions.size)
        assertTrue(result.omittedCount > 0)
        // Cada linha incluída contém a decisão inteira, nunca um prefixo.
        assertTrue(block.contains(long))
        assertFalse(block.contains(long.take(80) + "…"))
    }

    @Test
    fun blocoNaoExpoeIdsHashesNemTimestamps() {
        val result = build(
            sectionDecision(id = "id-secreto", text = "usar contraste"),
            noteDecision(id = "id-global"),
        )
        val block = result.block!!
        assertFalse(block.contains("id-secreto"))
        assertFalse(block.contains("hash-s1"))
        assertFalse(block.contains("createdAt"))
        assertFalse(block.contains("updatedAt"))
    }

    @Test
    fun chamadasRepetidasNoMesmoEstadoSaoIdenticas() {
        val decisions = listOf(noteDecision(), sectionDecision(), sectionDecision(id = "s2d", sectionId = "s2", order = 2))
        val first = DecisionPromptMemory.build(decisions, "n1", "s1", false)
        val second = DecisionPromptMemory.build(decisions.shuffled(), "n1", "s1", false)
        assertEquals(first, second)
    }

    @Test
    fun falhaDeLeituraNaoInventaMemoriaESuprimeLegado() = runBlocking {
        val result = loadDecisionPromptMemory(
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = false,
            load = { error("ledger indisponível") },
        )
        assertNull(result.block)
        assertFalse(result.available)
        assertTrue(result.suppressLegacyApproach)
        assertEquals(0, result.includedCount)
    }

    @Test
    fun cancelamentoDeCoroutinePropaga() = runBlocking {
        try {
            loadDecisionPromptMemory(
                noteId = "n1",
                focusSectionId = "s1",
                wholeNote = false,
                load = { throw CancellationException("cancelado") },
            )
            fail("esperava CancellationException")
        } catch (_: CancellationException) {
            // esperado
        }
    }

    @Test
    fun semNotaNaoSuprimeLegadoENaoChamaLedger() = runBlocking {
        var called = false
        val result = loadDecisionPromptMemory(
            noteId = null,
            focusSectionId = null,
            wholeNote = false,
            load = { called = true; emptyList() },
        )
        assertFalse(called)
        assertNull(result.block)
        assertFalse(result.suppressLegacyApproach)
        assertTrue(result.available)
    }

    @Test
    fun decisaoRemovidaNaoViraBlocoEPreservaSinalDeLedger() {
        val removed = noteDecision(state = DecisionState.REMOVED, text = "tom pastoral em toda a nota")

        val result = DecisionPromptMemory.build(
            decisions = listOf(removed),
            noteId = "n1",
            focusSectionId = "s1",
            wholeNote = true,
        )

        // Ledger preservado: o registro REMOVED permanece, não orienta o
        // prompt e o legado `agreedApproach` fica suprimido.
        assertFalse(removed.isEligibleForPromptMemory())
        assertNull(result.block)
        assertEquals(0, result.includedCount)
        assertTrue(result.hasLedgerRecords)
        assertTrue(result.suppressLegacyApproach)
    }
}
