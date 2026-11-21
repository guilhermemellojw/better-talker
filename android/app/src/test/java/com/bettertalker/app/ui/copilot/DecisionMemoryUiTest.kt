package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.ApplicationState
import com.bettertalker.app.data.copilot.DecisionKind
import com.bettertalker.app.data.copilot.DecisionRecord
import com.bettertalker.app.data.copilot.DecisionScope
import com.bettertalker.app.data.copilot.DecisionSource
import com.bettertalker.app.data.copilot.DecisionState
import com.bettertalker.app.data.copilot.DecisionSubject
import com.bettertalker.app.data.copilot.SectionLink
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1C.2.2A — mapeamento puro e honesto do ledger para a visão somente leitura. */
class DecisionMemoryUiTest {

    private val fixedDate: (Long) -> String = { "19/11/2026" }

    private fun decision(
        id: String,
        text: String = "decisão $id",
        noteId: String = "n1",
        scope: DecisionScope = DecisionScope.SECTION,
        kind: DecisionKind = DecisionKind.CONFIRMED,
        state: DecisionState = DecisionState.ACTIVE,
        link: SectionLink =
            if (scope == DecisionScope.SECTION) SectionLink.VALID else SectionLink.NOT_APPLICABLE,
        application: ApplicationState = ApplicationState.NOT_APPLIED,
        source: DecisionSource = DecisionSource.STRUCTURE_ACCEPT,
        sourceProposalId: String? = "prop-$id",
        subjectKey: String? = DecisionSubject.SECTION_APPROACH.key,
        supersedesId: String? = null,
        createdAt: Long = 10L,
        updatedAt: Long = createdAt,
    ): DecisionRecord {
        val section = scope == DecisionScope.SECTION
        return DecisionRecord(
            id = id,
            noteId = noteId,
            scope = scope,
            kind = kind,
            decisionState = state,
            sectionLink = link,
            applicationState = application,
            text = text,
            source = source,
            sectionId = if (section) "s1" else null,
            originalSectionId = if (section) "s1" else null,
            candidateSectionId =
                if (link == SectionLink.PENDING_RECONFIRMATION) "s2" else null,
            sectionTitleSnapshot = if (section) "Ponto 1" else null,
            sectionRoleSnapshot = if (section) SectionRole.BODY else null,
            sectionOrderSnapshot = if (section) 1 else null,
            sectionContentHashSnapshot = if (section) "hash-s1" else null,
            originalSectionTitleSnapshot = if (section) "Ponto 1" else null,
            originalSectionRoleSnapshot = if (section) SectionRole.BODY else null,
            originalSectionOrderSnapshot = if (section) 1 else null,
            originalSectionContentHashSnapshot = if (section) "hash-s1" else null,
            subjectKey = subjectKey,
            sourceProposalId = sourceProposalId,
            supersedesId = supersedesId,
            appliedAt = if (application == ApplicationState.NOT_APPLIED) null else 11L,
            appliedHash = if (application == ApplicationState.NOT_APPLIED) null else "hash-aplicado",
            createdAt = createdAt,
            updatedAt = updatedAt,
            removedAt = if (state == DecisionState.REMOVED) updatedAt else null,
        )
    }

    private fun build(vararg decisions: DecisionRecord) =
        buildDecisionMemoryUi(decisions.toList(), fixedDate)

    @Test
    fun vazioProduzEstadoVazio() {
        val state = build()

        assertTrue(state.isEmpty)
        assertTrue(state.active.isEmpty())
        assertTrue(state.attention.isEmpty())
        assertTrue(state.history.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun ativaConfirmadaMostraTextoOrigemDataEAplicacaoHonesta() {
        val state = build(decision("a", text = "Começar com uma pergunta."))

        val item = state.active.single()
        assertEquals("Começar com uma pergunta.", item.text)
        assertEquals("Ativa", item.statusLabel)
        assertEquals("Aceite de estrutura", item.originLabel)
        assertEquals("Ponto 1", item.sectionLabel)
        assertEquals("19/11/2026", item.dateLabel)
        assertEquals("Texto do discurso não alterado", item.applicationLabel)
        assertTrue(state.history.isEmpty())
        assertTrue(state.attention.isEmpty())
    }

    @Test
    fun isRemovableApenasParaConfirmadaAtiva() {
        val active = build(decision("a")).active.single()
        assertTrue(active.isRemovable)

        val attention = build(decision("b", link = SectionLink.ORPHANED)).attention.single()
        assertTrue(attention.isRemovable)

        val history = build(decision("c", state = DecisionState.SUPERSEDED)).history.single()
        assertFalse(history.isRemovable)

        val preference = build(decision("d", kind = DecisionKind.PREFERENCE, source = DecisionSource.EXPLICIT_USER)).history.single()
        assertFalse(preference.isRemovable)
    }

    @Test
    fun orfaPendenteEAplicacaoStaleVaoParaAtencao() {
        val orphan = decision("a", link = SectionLink.ORPHANED)
        val pending = decision("b", link = SectionLink.PENDING_RECONFIRMATION)
        val stale = decision(
            "c",
            application = ApplicationState.STALE,
        )

        val state = build(orphan, pending, stale)

        assertTrue(state.active.isEmpty())
        assertEquals(
            setOf("Sem seção", "Precisa reconfirmar", "Aplicação desatualizada"),
            state.attention.map { it.statusLabel }.toSet(),
        )
        assertTrue(state.history.isEmpty())
    }

    @Test
    fun aplicadaAoTextoEhExibidaSemInventar() {
        val state = build(decision("a", application = ApplicationState.APPLIED))

        val item = state.active.single()
        assertEquals("Ativa", item.statusLabel)
        assertEquals("Aplicada ao texto", item.applicationLabel)
    }

    @Test
    fun substituidaERelacaoSaoExibidasNoHistorico() {
        val previous = decision(
            "a",
            text = "Antes",
            createdAt = 10L,
            state = DecisionState.SUPERSEDED,
        )
        val replacement = decision(
            "b",
            text = "Agora",
            supersedesId = "a",
            createdAt = 20L,
            state = DecisionState.SUPERSEDED,
        )
        val active = decision("c", text = "Atual", supersedesId = "b", createdAt = 30L)

        val state = build(previous, replacement, active)

        assertEquals(1, state.active.size)
        assertEquals("Atual", state.active.single().text)
        assertEquals("Agora", state.active.single().replacesText)
        val history = state.history.single { it.id == "a" }
        assertEquals("Substituída", history.statusLabel)
        assertEquals("Agora", history.replacedByText)
    }

    @Test
    fun removidaVaiParaHistoricoSemPrometerAplicacao() {
        val state = build(
            decision("a", state = DecisionState.REMOVED, application = ApplicationState.APPLIED),
        )

        val item = state.history.single()
        assertEquals("Removida", item.statusLabel)
        assertEquals("Aplicada ao texto no histórico", item.applicationLabel)
        assertTrue(state.active.isEmpty())
    }

    @Test
    fun naturezaRecusadaNaoEhApresentadaComoAtiva() {
        val state = build(
            decision(
                "a",
                kind = DecisionKind.REJECTED,
                source = DecisionSource.EXPLICIT_USER,
            ),
        )

        assertTrue(state.active.isEmpty())
        assertEquals("Recusada", state.history.single().statusLabel)
    }

    @Test
    fun origemLegadaSemPropostaNaoEhAtribuidaAoUsuario() {
        val state = build(
            decision("a", source = DecisionSource.STRUCTURE_ACCEPT, sourceProposalId = null),
        )

        assertEquals("Registro anterior", state.active.single().originLabel)
    }

    @Test
    fun notaInteiraTemRotuloProprio() {
        val state = build(
            decision(
                "a",
                scope = DecisionScope.NOTE,
                subjectKey = DecisionSubject.NOTE_TONE.key,
            ),
        )

        assertEquals("Nota inteira", state.active.single().sectionLabel)
    }

    @Test
    fun ordenaMaisRecentePrimeiro() {
        val state = build(
            decision("a", createdAt = 10L),
            decision("b", createdAt = 30L),
            decision("c", createdAt = 20L),
        )

        assertEquals(listOf("b", "c", "a"), state.active.map { it.id })
    }

    @Test
    fun textoLongoNaoEhTruncadoNoModelo() {
        val longText = "x".repeat(10_000)
        val state = build(decision("a", text = longText))

        assertEquals(longText, state.active.single().text)
    }

    @Test
    fun rotulosNaoExpoemEnumsNemHash() {
        val state = build(decision("a"))

        val labels = listOf(
            state.active.single().statusLabel,
            state.active.single().originLabel,
            state.active.single().applicationLabel,
            state.active.single().sectionLabel,
        ).joinToString(" ")
        assertFalse(labels.contains("ACTIVE"))
        assertFalse(labels.contains("STRUCTURE_ACCEPT"))
        assertFalse(labels.contains("hash"))
        assertNotNull(state.active.single().id)
    }
}
