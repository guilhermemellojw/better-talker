package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Referent
import com.bettertalker.app.data.copilot.PedagogicalMinIntent.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PEDAGOGY-1-MIN / P1 — contrato puro, sem integração produtiva. */
class PedagogicalMinIntentTest {

    private fun card(note: String = "sec-1", label: String = "Romanos 8") = CardPayload(
        targetKind = CardTargetKind.SECTION,
        targetId = note,
        targetLabel = label,
        baseHash = "h",
    )

    private fun ctx(
        note: String = "n1",
        section: String? = "sec-1",
        focus: Focus = Focus.SECTION,
        c: CardPayload? = card(),
        cardNote: String? = note,
        cardSection: String? = "sec-1",
    ) = PedagogicalMinIntent.Context(
        noteId = note, sectionId = section, focus = focus,
        lastCard = c, lastCardNoteId = cardNote, lastCardSectionId = cardSection,
    )

    private fun resolve(request: String, context: PedagogicalMinIntent.Context = ctx()) =
        PedagogicalMinIntent.resolve(request, context)

    // ---- 1 — reformulação / simplificação ----

    @Test
    fun p1_expliqueDeOutraManeira_reformula() {
        val r = resolve("Explique de outra maneira.")
        assertTrue(r is Outcome.Reformulate)
        assertFalse((r as Outcome.Reformulate).simplify)
        assertTrue(r.referent is Referent.DeepenCard)
    }

    @Test
    fun p1_expliqueMaisSimples_simplifica() {
        val r = resolve("Explique mais simples, por favor.")
        assertTrue(r is Outcome.Reformulate)
        assertTrue((r as Outcome.Reformulate).simplify)
    }

    @Test
    fun p1_reformuleSimplifique_equivalentes() {
        assertTrue(resolve("Reformule essa explicação.") is Outcome.Reformulate)
        assertTrue(resolve("Simplifique.") is Outcome.Reformulate)
        assertTrue(resolve("Diga de outro jeito.") is Outcome.Reformulate)
    }

    // ---- 2 — desenvolver "essa ideia" pelo referente ----

    @Test
    fun p2_comoDesenvolverEssaIdeia_usaReferente() {
        val r = resolve("Como posso desenvolver essa ideia?")
        assertTrue(r is Outcome.DevelopReferent)
        assertTrue((r as Outcome.DevelopReferent).referent is Referent.DeepenCard)
    }

    @Test
    fun p2_desenvolvaEssaIdeia_usaReferente() {
        assertTrue(resolve("Desenvolva essa ideia.") is Outcome.DevelopReferent)
    }

    // ---- 3 — feedback positivo sem efeito ----

    @Test
    fun p3_gosteiDaSugestao_apenasReconhece() {
        val before = ctx()
        val r = resolve("Gostei dessa sugestão.", before)
        assertEquals(Outcome.PositiveFeedback, r)
        // Nenhum estado é alterado — não há aceite persistido nem inserção.
        assertEquals(before, ctx())
        assertEquals(Outcome.PositiveFeedback, resolve("Gostei muito!"))
        assertEquals(Outcome.PositiveFeedback, resolve("Boa sugestão."))
    }

    // ---- controles negativos: fluxos certificados preservados ----

    @Test
    fun n1_coloqueNoRascunho_delega() {
        assertEquals(Outcome.Delegate, resolve("Coloque essa ideia no rascunho."))
        assertEquals(Outcome.Delegate, resolve("Insira essa ideia na nota."))
        assertEquals(Outcome.Delegate, resolve("Gostei, coloque no rascunho."))
    }

    @Test
    fun n2_secaoExplicita_delega() {
        assertEquals(Outcome.Delegate, resolve("Desenvolva o ponto 2."))
        assertEquals(Outcome.Delegate, resolve("Explique melhor Romanos 8."))
    }

    @Test
    fun n3_confirmacoesCurta_delegam() {
        assertEquals(Outcome.Delegate, resolve("Sim"))
        assertEquals(Outcome.Delegate, resolve("Ok"))
        assertEquals(Outcome.Delegate, resolve("Entendi"))
    }

    @Test
    fun n4_aprofundamentoCertificado_delega() {
        assertEquals(Outcome.Delegate, resolve("Não entendi esse ponto."))
        assertEquals(Outcome.Delegate, resolve("Vamos continuar de onde paramos."))
    }

    @Test
    fun n5_relacaoBiblica_adiadaAoTrust() {
        assertEquals(Outcome.Delegate, resolve("O que esse texto bíblico tem a ver com o tema?"))
    }

    @Test
    fun n6_mensagemVazia_delega() {
        assertEquals(Outcome.Delegate, resolve("   "))
    }

    // ---- identidade / ambiguidade ----

    @Test
    fun i1_semReferente_esclarece() {
        val c = ctx(section = null, focus = Focus.WHOLE, c = null, cardNote = null, cardSection = null)
        assertEquals(Outcome.Clarify, resolve("Explique de outra maneira.", c))
    }

    @Test
    fun i2_notaIncompativel_naoReformulaCard() {
        val c = ctx(note = "n2", cardNote = "n1")
        val r = resolve("Explique de outra maneira.", c)
        assertFalse(r is Outcome.Reformulate && r.referent is Referent.DeepenCard)
    }

    @Test
    fun i3_secaoIncompativel_naoDesenvolveCard() {
        val c = ctx(section = "sec-2", cardSection = "sec-1")
        val r = resolve("Desenvolva essa ideia.", c)
        assertFalse(r is Outcome.DevelopReferent && r.referent is Referent.DeepenCard)
    }

    @Test
    fun i4_focoWhole_comCardDeNota_podeReformular() {
        val noteCard = card().copy(targetKind = CardTargetKind.NOTE, targetId = "n1")
        val c = ctx(section = null, focus = Focus.WHOLE, c = noteCard, cardSection = null)
        assertTrue(resolve("Explique de outra maneira.", c) is Outcome.Reformulate)
    }

    @Test
    fun i5_sugestaoComOrigem_podeSerReformulada() {
        val s = NextStepSuggestion(
            label = "Desenvolva o ponto 1", detail = "Escreva o texto.",
            action = ResolvedChatNav.DevelopCurrent("sec-1", "Ponto 1"),
        )
        val c = ctx().copy(
            lastCard = null, lastCardNoteId = null, lastCardSectionId = null,
            lastSuggestion = s, lastSuggestionNoteId = "n1", lastSuggestionSectionId = "sec-1",
        )
        val r = resolve("Explique de outra maneira.", c)
        assertTrue(r is Outcome.Reformulate)
        assertTrue((r as Outcome.Reformulate).referent is Referent.ExplainSuggestion)
    }


    // ---- P2 / F1 — simplify=true para verbos de simplificação ----

    @Test
    fun f1_simplifiqueEEquivalentes_campoSimplifyVerdadeiro() {
        listOf("Simplifique.", "Simplificar.", "Simplifique essa explicação.", "Explique mais simples.")
            .forEach { req ->
                val r = resolve(req)
                assertTrue("$req -> $r", r is Outcome.Reformulate)
                assertTrue("$req deve simplificar", (r as Outcome.Reformulate).simplify)
            }
    }

    // ---- P2 / F2 — feedback positivo exige ausência de negação/misto ----

    @Test
    fun f2_negacaoENaoAprovacao_nFeedbackPositivo() {
        listOf(
            "Não gostei dessa sugestão.",
            "Não concordo.",
            "Isso não ficou legal.",
            "Gostei, mas escreva outra introdução.",
        ).forEach { req ->
            assertEquals("$req -> $req", Outcome.Delegate, resolve(req))
        }
    }

    // ---- P2 / F3 — referência explícita não pode ser eliminada ----

    @Test
    fun f3_referenciaExplicitaIncompativel_naoSelecionaCardAnterior() {
        val divergente = ctx(c = card(label = "Gênesis 1:1"))
        listOf("Simplifique Romanos 8.", "Explique de outra maneira João 3:16.")
            .forEach { req ->
                val r = resolve(req, divergente)
                assertFalse("$req -> $r", r is Outcome.Reformulate &&
                    r.referent is Referent.DeepenCard)
            }
        assertEquals(Outcome.Delegate, resolve("Reformule o ponto 2.", divergente))
    }

    // ---- P2 / F4 — aprofundar ≠ desenvolver ----

    @Test
    fun f4_aprofundeEssaIdeia_naoEhDesenvolvimento() {
        assertEquals(Outcome.Delegate, resolve("Aprofunde essa ideia."))
        assertTrue(resolve("Desenvolva essa ideia.") is Outcome.DevelopReferent)
    }

    @Test
    fun f3_referenciaExplicitaCompativel_reformulaCardCorreto() {
        val r = resolve("Simplifique Romanos 8.", ctx(c = card(label = "Romanos 8")))
        assertTrue(r is Outcome.Reformulate)
        assertTrue((r as Outcome.Reformulate).referent is Referent.DeepenCard)
        assertTrue((r.referent as Referent.DeepenCard).card.targetLabel == "Romanos 8")
    }


    // ---- P3 / F5 — negação não dispara a ação negada ----

    @Test
    fun f5_pedidosNegados_delegam() {
        listOf(
            "Não simplifique essa explicação.",
            "Não reformule.",
            "Não desenvolva essa ideia.",
            "Não simplifique, mas reformule.",
        ).forEach { req ->
            assertEquals("$req -> $req", Outcome.Delegate, resolve(req))
        }
    }

    @Test
    fun f5_pedidosPositivos_preservados() {
        assertTrue(resolve("Simplifique essa explicação.") is Outcome.Reformulate)
        assertTrue(resolve("Reformule.") is Outcome.Reformulate)
        assertTrue(resolve("Desenvolva essa ideia.") is Outcome.DevelopReferent)
    }

    // ---- P3 / F6 — identidade exata da referência explícita ----

    private fun withCard(label: String) = ctx(c = card(label = label))

    @Test
    fun f6_romanos1NaoCasaRomanos10_nosDoisSentidos() {
        val r1 = resolve("Simplifique Romanos 10.", withCard("Romanos 1"))
        assertFalse("Romanos 1 não é Romanos 10: $r1",
            r1 is Outcome.Reformulate && r1.referent is Referent.DeepenCard)
        val r2 = resolve("Simplifique Romanos 1.", withCard("Romanos 10"))
        assertFalse("Romanos 10 não é Romanos 1: $r2",
            r2 is Outcome.Reformulate && r2.referent is Referent.DeepenCard)
    }

    @Test
    fun f6_joao31NaoCasaJoao310() {
        val r = resolve("Explique de outra maneira João 3:10.", withCard("João 3:1"))
        assertFalse("João 3:1 não é João 3:10: $r",
            r is Outcome.Reformulate && r.referent is Referent.DeepenCard)
    }

    @Test
    fun f6_referenciaCorretaAssociadaASugestao_selecionaSugestao() {
        val s = NextStepSuggestion(
            label = "Romanos 10", detail = "Leia o texto.",
            action = ResolvedChatNav.DevelopCurrent("sec-1", "Romanos 10"),
        )
        val c = ctx(c = card(label = "Romanos 1")).copy(
            lastSuggestion = s, lastSuggestionNoteId = "n1", lastSuggestionSectionId = "sec-1",
        )
        val r = resolve("Simplifique Romanos 10.", c)
        assertTrue("deve selecionar a sugestão: $r", r is Outcome.Reformulate)
        assertTrue((r as Outcome.Reformulate).referent is Referent.ExplainSuggestion)
    }
}
