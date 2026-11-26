package com.bettertalker.app.ui.copilot

import com.bettertalker.app.data.copilot.CardPayload
import com.bettertalker.app.data.copilot.CardTargetKind
import com.bettertalker.app.data.copilot.ChatBiblicalSupport
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Referent
import com.bettertalker.app.data.copilot.NextStepSuggestion
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge.Context
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge.Dispatch
import com.bettertalker.app.data.copilot.PedagogicalContinuityBridge.Source
import com.bettertalker.app.data.copilot.ResolvedChatNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEDAGOGY-1-MIN / P6 — integração produtiva da ponte certificada.
 *
 * Fail-first: estes testes descrevem o seam único de `send()` e a
 * simplificação efetiva ANTES da implementação. Nenhum LLM, Room ou
 * dispositivo: o comportamento integrado é testado pela fronteira pura
 * que o ViewModel chama e executa dentro do ciclo canônico do turno.
 */
class PedagogicalIntegrationTest {

    private fun card(label: String = "Romanos 8", target: String = "sec-1") = CardPayload(
        targetKind = CardTargetKind.SECTION, targetId = target,
        targetLabel = label, baseHash = "h",
    )

    private fun suggestion(label: String = "Romanos 8") = NextStepSuggestion(
        label = label, detail = "Leia o trecho.",
        action = ResolvedChatNav.DevelopCurrent("sec-1", label),
    )

    private fun ctx(
        note: String = "n1",
        section: String? = "sec-1",
        c: CardPayload? = card(),
        cardNote: String? = note,
        cardSection: String? = "sec-1",
        s: NextStepSuggestion? = null,
        sNote: String? = note,
        sSection: String? = "sec-1",
        consent: Boolean = false,
        command: Boolean = false,
    ) = Context(
        noteId = note, sectionId = section, focus = Focus.SECTION,
        lastCard = c, lastCardNoteId = cardNote, lastCardSectionId = cardSection,
        lastSuggestion = s, lastSuggestionNoteId = sNote, lastSuggestionSectionId = sSection,
        pendingConsent = consent, explicitCommand = command,
    )

    private val titles = mapOf("sec-1" to "Introdução", "sec-2" to "Romanos 8")

    @Test
    fun i01_reformulacao_usaOTemaDoReferente() {
        val d = PedagogicalContinuityBridge.dispatch("Explique de outra maneira.", ctx())
        val action = planPedagogicalAction(d, titles)
        assertTrue(action is PedagogicalAction.Reformulate)
        val r = action as PedagogicalAction.Reformulate
        assertEquals("Romanos 8", r.topic)
        assertFalse(r.simplify)
    }

    @Test
    fun i02_simplificacao_eEfeitoReal_semTocarCitacaoNemReferencia() {
        val d = PedagogicalContinuityBridge.dispatch("Simplifique.", ctx())
        val action = planPedagogicalAction(d, titles)
        assertTrue(action is PedagogicalAction.Reformulate)
        assertTrue((action as PedagogicalAction.Reformulate).simplify)

        val raw = "Sobre “Romanos 8”, basicamente, em outras palavras, o texto diz: " +
            "“O amor é paciente”. É importante ressaltar que João 3:16 fala do amor."
        val out = simplifyPedagogicalText(raw)
        assertTrue(out.contains("“O amor é paciente”"))
        assertTrue(out.contains("João 3:16"))
        // P9/beta: `basicamente` exprime grau e é preservado (redução deliberada
        // do escopo, autorizada pela coordenação); `em outras palavras` é
        // marcador de reformulação e continua removível.
        assertTrue(out.contains("basicamente"))
        assertFalse(out.contains("em outras palavras"))
        assertFalse(out.contains("É importante ressaltar"))
        assertTrue("simplificação precisa encurtar de verdade: $out", out.length < raw.length)
        assertFalse(out.contains("Inventei")) // nada é acrescentado como evidência
    }

    @Test
    fun i02b_simplificacao_preservaRotuloDeTrechoRecuperado() {
        val rotulo = ChatBiblicalSupport.retrievedExcerpt("TNM", "texto real recuperado")
        assertNotEquals(null, rotulo)
        val raw = "Basicamente, $rotulo — João 3:16."
        val out = simplifyPedagogicalText(raw)
        assertTrue(out.contains(rotulo!!))
        assertTrue(out.contains("João 3:16"))
        assertTrue("P9/beta: grau/abrangência preservado", out.contains("Basicamente"))
    }

    @Test
    fun i03_desenvolvimento_usaSecaoDoReferenteNaoAAtiva() {
        // Card pertence a sec-2; o foco atual é sec-1. O tema tem que virar
        // do card, nunca da seção ativa.
        val referent = Referent.DeepenCard(card(label = "Romanos 8", target = "sec-2"))
        val action = planPedagogicalAction(
            Dispatch.Develop(referent, Source.CARD, "n1", "sec-2"), titles,
        )
        assertTrue(action is PedagogicalAction.Develop)
        assertEquals("Romanos 8", (action as PedagogicalAction.Develop).topic)

        val bySection = planPedagogicalAction(
            Dispatch.Develop(Referent.ExplainActiveSection("sec-2"), Source.SECTION, "n1", "sec-2"),
            titles,
        )
        assertEquals("Romanos 8", (bySection as PedagogicalAction.Develop).topic)
    }

    @Test
    fun i04_feedbackPositivo_semTopicSemAceiteSemInsercao() {
        val d = PedagogicalContinuityBridge.dispatch("Gostei dessa sugestão.", ctx())
        assertEquals(Dispatch.PositiveFeedback, d)
        val action = planPedagogicalAction(d, titles)
        assertEquals(PedagogicalAction.PositiveFeedback, action)
        // data object: nenhum topic, nenhum destino, nenhum avanço de estado.
        assertTrue(action !is PedagogicalAction.Reformulate)
        assertTrue(action !is PedagogicalAction.Develop)
    }

    @Test
    fun i05_origemIncompativel_esclareceLocalmente() {
        val d = PedagogicalContinuityBridge.dispatch(
            "Simplifique Romanos 8.", ctx(note = "n2", cardNote = "n1"),
        )
        assertTrue(d is Dispatch.Clarify)
        val action = planPedagogicalAction(d, titles)
        assertTrue(action is PedagogicalAction.Clarify)
        assertTrue((action as PedagogicalAction.Clarify).reason.isNotBlank())
        assertTrue(action !is PedagogicalAction.Reformulate)
    }

    @Test
    fun i06_negacaoEIdentidadeExata_preservadasNaIntegracao() {
        assertEquals(
            Dispatch.Delegate,
            PedagogicalContinuityBridge.dispatch("Não simplifique.", ctx()),
        )
        assertTrue(
            PedagogicalContinuityBridge.dispatch(
                "Simplifique Romanos 10.", ctx(c = card(label = "Romanos 1")),
            ) is Dispatch.Clarify,
        )
        assertEquals(
            PedagogicalAction.Delegate,
            planPedagogicalAction(
                PedagogicalContinuityBridge.dispatch("Não desenvolva essa ideia.", ctx()), titles,
            ),
        )
    }

    @Test
    fun i07_precedenciaDeConsentimentoEComando_delega() {
        assertEquals(
            PedagogicalAction.Delegate,
            planPedagogicalAction(
                PedagogicalContinuityBridge.dispatch("Simplifique.", ctx(consent = true)), titles,
            ),
        )
        assertEquals(
            PedagogicalAction.Delegate,
            planPedagogicalAction(
                PedagogicalContinuityBridge.dispatch("Simplifique.", ctx(command = true)), titles,
            ),
        )
    }

    @Test
    fun i08_apenasAcoesLocais_gateEVerificadorContinuamAplicaveis() {
        val local = planPedagogicalAction(
            PedagogicalContinuityBridge.dispatch("Reformule.", ctx()), titles,
        )
        // Nenhuma ação da ponte autoriza provedor, inserção ou desativação de gate.
        assertTrue(
            local is PedagogicalAction.Reformulate ||
                local is PedagogicalAction.Develop ||
                local is PedagogicalAction.Clarify ||
                local === PedagogicalAction.PositiveFeedback ||
                local === PedagogicalAction.Delegate,
        )
        // O texto simplificado continua passando pelo verificador TRUST #60.
        val simplified = simplifyPedagogicalText("Basicamente, João 3:16 diz: “texto literal”.")
        val out = verifyChatText(simplified, listOf("texto literal recuperado"))
        assertTrue(out.text.contains("“texto literal”"))
        assertTrue("P9/beta: qualificador preservado; TRUST #60 intacto", out.text.contains("Basicamente"))
    }

    @Test
    fun i09_plannerDeterministico_semEstadoMutavel() {
        val d1 = PedagogicalContinuityBridge.dispatch("Simplifique.", ctx())
        val d2 = PedagogicalContinuityBridge.dispatch("Simplifique.", ctx())
        assertEquals(d1, d2)
        assertEquals(planPedagogicalAction(d1, titles), planPedagogicalAction(d2, titles))
        // O contexto é imutável: a ponte não consome sugestão nem card.
        val c = ctx(s = suggestion())
        PedagogicalContinuityBridge.dispatch("Gostei.", c)
        assertEquals(suggestion(), c.lastSuggestion)
    }

    @Test
    fun i10_regeneracaoOuEdicao_mesmoTextoMesmaAcao() {
        val request = "Desenvolva essa ideia."
        val c = ctx()
        val a = planPedagogicalAction(PedagogicalContinuityBridge.dispatch(request, c), titles)
        val b = planPedagogicalAction(PedagogicalContinuityBridge.dispatch(request, c), titles)
        assertEquals(a, b)
        assertTrue(a is PedagogicalAction.Develop)
    }

    @Test
    fun i11_simplicaoNaoAlteraTextoJSimplesNemCriado() {
        val texto = "“citação literal” — João 3:16."
        assertEquals(texto, simplifyPedagogicalText(texto))
        assertEquals(simplifyPedagogicalText(texto), simplifyPedagogicalText(simplifyPedagogicalText(texto)))
    }
}
