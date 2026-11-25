package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Referent
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge.Context
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge.Dispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5 — contrato puro da ponte; 27 testes do P3 permanecem intactos. */
class PedagogicalContinuityBridgeTest {

    private fun card(label: String = "Romanos 8", target: String = "sec-1") = CardPayload(
        targetKind = CardTargetKind.SECTION, targetId = target,
        targetLabel = label, baseHash = "h",
    )

    private fun ctx(
        note: String = "n1",
        section: String? = "sec-1",
        c: CardPayload? = card(),
        cardNote: String? = note,
        cardSection: String? = "sec-1",
        consent: Boolean = false,
        command: Boolean = false,
    ) = Context(
        noteId = note, sectionId = section, focus = Focus.SECTION,
        lastCard = c, lastCardNoteId = cardNote, lastCardSectionId = cardSection,
        pendingConsent = consent, explicitCommand = command,
    )

    @Test
    fun b01_reformulacao_carregaReferenteENotaSecao() {
        val d = PedagogicalContinuityBridge.dispatch("Explique de outra maneira.", ctx())
        assertTrue(d is Dispatch.Reformulate)
        val r = d as Dispatch.Reformulate
        assertTrue(r.referent is Referent.DeepenCard)
        assertEquals("n1", r.noteId)
        assertEquals("sec-1", r.sectionId)
        assertEquals(PedagogicalContinuityBridge.Source.CARD, r.source)
        assertFalse("apenas flags, sem comportamento de simplificação", r.simplify)
    }

    @Test
    fun b02_simplificacao_eFlagDeIntencao() {
        val d = PedagogicalContinuityBridge.dispatch("Simplifique.", ctx())
        assertTrue(d is Dispatch.Reformulate && (d as Dispatch.Reformulate).simplify)
    }

    @Test
    fun b03_desenvolver_usaSecaoDoReferente() {
        val d = PedagogicalContinuityBridge.dispatch("Desenvolva essa ideia.", ctx())
        assertTrue(d is Dispatch.Develop)
        val r = d as Dispatch.Develop
        assertEquals("sec-1", r.sectionId)
        assertEquals(PedagogicalContinuityBridge.Source.CARD, r.source)
    }

    @Test
    fun b04_feedbackPositivo_semEstadoOuAceite() {
        val d = PedagogicalContinuityBridge.dispatch("Gostei dessa sugestão.", ctx())
        assertEquals(Dispatch.PositiveFeedback, d)
        // data object: nenhum campo de aceite, avanço ou inserção na saída.
        assertTrue(Dispatch.PositiveFeedback === Dispatch.PositiveFeedback)
    }

    @Test
    fun b05_origemIncompativel_esclarece() {
        val d = PedagogicalContinuityBridge.dispatch("Simplifique Romanos 8.", ctx(note = "n2", cardNote = "n1"))
        assertTrue(d is Dispatch.Clarify)
    }

    @Test
    fun b06_identidadeExata_romanos1NaoCasa10() {
        val d = PedagogicalContinuityBridge.dispatch("Simplifique Romanos 10.", ctx(c = card(label = "Romanos 1")))
        assertTrue(d is Dispatch.Clarify)
    }

    @Test
    fun b07_negacao_delega() {
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Não simplifique.", ctx()))
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Não desenvolva essa ideia.", ctx()))
    }

    @Test
    fun b08_consentimentoPendente_temPrecedencia() {
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Gostei dessa sugestão.", ctx(consent = true)))
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Simplifique.", ctx(consent = true)))
    }

    @Test
    fun b09_comandoExplicito_temPrecedencia() {
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Simplifique.", ctx(command = true)))
    }

    @Test
    fun b10_delegacaoCertificada_preservada() {
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Não entendi esse ponto.", ctx()))
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Coloque essa ideia no rascunho.", ctx()))
        assertEquals(Dispatch.Delegate, PedagogicalContinuityBridge.dispatch("Desenvolva o ponto 2.", ctx()))
    }

    @Test
    fun b11_semReferente_esclarece() {
        val c = ctx(section = null, c = null, cardNote = null, cardSection = null)
        val d = PedagogicalContinuityBridge.dispatch("Explique de outra maneira.", c)
        assertTrue(d is Dispatch.Clarify)
    }

    @Test
    fun b12_sugestaoComOrigem_selecionaSugestao() {
        val s = NextStepSuggestion(
            label = "Romanos 8", detail = "Leia.",
            action = ResolvedChatNav.DevelopCurrent("sec-1", "Romanos 8"),
        )
        val c = ctx(c = null, cardNote = null, cardSection = null).copy(
            lastSuggestion = s, lastSuggestionNoteId = "n1", lastSuggestionSectionId = "sec-1",
        )
        val d = PedagogicalContinuityBridge.dispatch("Simplifique Romanos 8.", c)
        assertTrue(d is Dispatch.Reformulate)
        assertEquals(PedagogicalContinuityBridge.Source.SUGGESTION, (d as Dispatch.Reformulate).source)
    }
}
