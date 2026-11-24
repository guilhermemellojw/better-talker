package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Context
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Focus
import com.bettertalker.app.data.copilot.ConversationalReferentResolver.Referent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEDAGOGY-0A / P3 + P2-FIX — cenários adversariais do resolvedor de
 * referente conversacional. Deterministas, sem LLM/rede.
 */
class ConversationalReferentResolverTest {

    private fun card(
        targetId: String? = "sec-1",
        targetLabel: String = "Romanos 8",
        status: CardStatus = CardStatus.AVAILABLE,
        kind: CardTargetKind = CardTargetKind.SECTION,
    ) = CardPayload(
        targetKind = kind,
        targetId = targetId,
        targetLabel = targetLabel,
        status = status,
    )

    private fun suggestion(label: String = "Desenvolva o ponto 1") =
        NextStepSuggestion(
            label = label,
            detail = "",
            action = ResolvedChatNav.DevelopCurrent(sectionId = "sec-1", title = "Ponto 1"),
        )

    /** Contexto com origem confirmada (F3 — P2-FIX). */
    private fun ctxCard(
        note: String = "n1",
        scope: String? = "sec-1",
        c: CardPayload = card(),
        cardNote: String? = note,
        cardSection: String? = "sec-1",
        focus: Focus = Focus.SECTION,
    ) = Context(
        noteId = note, chatScope = scope, focus = focus,
        lastCard = c, lastCardNoteId = cardNote, lastCardSectionId = cardSection,
    )

    private fun ctxSuggestion(
        note: String = "n1",
        scope: String? = "sec-1",
        s: NextStepSuggestion = suggestion(),
        sNote: String? = note,
        sSection: String? = "sec-1",
        focus: Focus = Focus.SECTION,
    ) = Context(
        noteId = note, chatScope = scope, focus = focus,
        lastSuggestion = s, lastSuggestionNoteId = sNote, lastSuggestionSectionId = sSection,
    )

    // ---- 14 cenários originais (P3) ----

    @Test
    fun a_expliqueMelhor_aposCard_aprofundaCard() {
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Explique melhor", ctxCard()))
    }

    @Test
    fun b_expliqueMelhor_aposSugestao_explicaSugestao() {
        val s = suggestion()
        assertEquals(Referent.ExplainSuggestion(s), ConversationalReferentResolver.resolve("Explique melhor", ctxSuggestion(s = s)))
    }

    @Test
    fun c_expliqueMelhor_semReferente_pedeEsclarecimento() {
        val r = ConversationalReferentResolver.resolve("Explique melhor", Context(noteId = "n1", chatScope = null))
        assertTrue(r is Referent.RequestClarification)
    }

    @Test
    fun d_naoEntendi_aposExplicacao_aprofundaCard() {
        val ctx = ctxCard().copy(lastResponse = "Então o ponto é ...")
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Não entendi", ctx))
    }

    @Test
    fun e_daoOutroExemplo_preservaAssunto_aprofundaCard() {
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Dá outro exemplo", ctxCard()))
    }

    @Test
    fun f_trocaSecao_A_para_B_explicaTopicoAtivo() {
        // Card antigo do ponto A; o card é rejeitado, o tópico B é o referente.
        val ctx = ctxCard(scope = "sec-2", c = card(targetId = "sec-1"), cardSection = "sec-1")
        assertEquals(Referent.ExplainActiveSection("sec-2"), ConversationalReferentResolver.resolve("Explique melhor", ctx))
    }

    @Test
    fun g_trocaNota_comSecaoAtiva_explicaSecaoAtiva() {
        val ctx = ctxCard(note = "n2", scope = "sec-5", cardNote = "n1", cardSection = "sec-1")
        assertEquals(Referent.ExplainActiveSection("sec-5"), ConversationalReferentResolver.resolve("Explique melhor", ctx))
    }

    @Test
    fun g2_trocaNota_semSecaoAtiva_pedeEsclarecimento() {
        // F4 — corrigido na lógica: card sem origem confirmada e sem seção
        // ativa não pode ser aprofundado.
        val ctx = Context(noteId = "n2", chatScope = null, lastCard = card(targetId = "sec-1"))
        val r = ConversationalReferentResolver.resolve("Explique melhor", ctx)
        assertTrue(r is Referent.RequestClarification)
    }

    @Test
    fun h_explicarMelhorRomanos8_casaCard_aprofundaCard() {
        val c = card(targetId = "sec-1", targetLabel = "Romanos 8")
        assertEquals(Referent.DeepenCard(c), ConversationalReferentResolver.resolve("Explique melhor Romanos 8", ctxCard(c = c)))
    }

    @Test
    fun i_retomadaAposInterrupcao_resumeReferente() {
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Vamos continuar de onde paramos", ctxCard()))
    }

    @Test
    fun j_referenteRemovido_pedeEsclarecimento() {
        val r = ConversationalReferentResolver.resolve("Explique melhor", Context(noteId = "n1", chatScope = null))
        assertTrue(r is Referent.RequestClarification)
    }

    @Test
    fun k_ausenciaFontesDocumentais_resolveReferente() {
        // Sem fontes documentais o resolvedor resolve o referente — sem
        // declarar verificação factual.
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Explique melhor", ctxCard()))
    }

    @Test
    fun l_pedidoExplicitoRascunho_prossegueFluxoNormal() {
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Desenvolva o ponto 1", ctxCard()))
    }

    @Test
    fun p4_mensagensCurtasSemVerboDeAprofundamento_prossegueFluxoNormal() {
        val ctx = Context(noteId = "n1", chatScope = null)
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Ok", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Sim", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Entendi", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Qual a próxima?", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Mensagem curta sem verbo", ctx))
    }

    // ---- F2 — DEEPEN: frases, acentuação/espaçamento e controles negativos ----

    @Test
    fun f2_deepeningFrasesReconhecidas() {
        val requests = listOf(
            "Explique melhor", "  EXPLIQUE   MELHOR  ", "Não entendi", "Nao entendi",
            "Dá outro exemplo", "Dê outro exemplo", "Fale mais",
            "Vamos continuar de onde paramos", "continuar de onde paramos",
        )
        requests.forEach { req ->
            assertTrue("esperava aprofundamento: $req", ConversationalReferentResolver.resolve(req, ctxCard()) is Referent.DeepenCard)
        }
    }

    @Test
    fun f2_controlesNegativos_saudacoesEConfirmacoes() {
        val ctx = ctxCard()
        listOf("Oi", "Olá", "Bom dia", "Sim", "Ok", "Entendi", "Valeu").forEach { req ->
            assertEquals("esperava ProceedNormal: $req", Referent.ProceedNormal, ConversationalReferentResolver.resolve(req, ctx))
        }
    }

    // ---- F3 — identidade do referente (metadados de origem no Context) ----

    @Test
    fun f3_CompatibilidadeDeNota_cardDeOutraNotaNaoEAprofundado() {
        val ctx = ctxCard(note = "n2", cardNote = "n1")
        assertTrue(ConversationalReferentResolver.resolve("Explique melhor", ctx) !is Referent.DeepenCard)
    }

    @Test
    fun f3_CompatibilidadeDeSecao_cardDeOutraSecaoNaoEAprofundado() {
        val ctx = ctxCard(scope = "sec-2", cardSection = "sec-1")
        assertTrue(ConversationalReferentResolver.resolve("Explique melhor", ctx) !is Referent.DeepenCard)
    }

    @Test
    fun f3_focoWhole_semSecaoAtiva_pedeEsclarecimento() {
        val ctx = ctxCard(focus = Focus.WHOLE, scope = "sec-1")
        assertTrue(ConversationalReferentResolver.resolve("Explique melhor", ctx) is Referent.RequestClarification)
    }

    @Test
    fun f3_cardInvalido_naoEAprofundado() {
        val ctx = ctxCard(c = card(status = CardStatus.APPLIED))
        assertTrue(ConversationalReferentResolver.resolve("Explique melhor", ctx) !is Referent.DeepenCard)
    }

    @Test
    fun f3_sugestaoDeSecaoAntiga_naoEExplicada() {
        val ctx = ctxSuggestion(scope = "sec-2", sSection = "sec-1")
        assertTrue(ConversationalReferentResolver.resolve("Explique melhor", ctx) !is Referent.ExplainSuggestion)
    }

    @Test
    fun f3_origemNaoConfirmada_pedeEsclarecimento() {
        // Sem metadados de origem, nem card nem sugestão podem ser usados.
        val ctx = Context(noteId = "n1", chatScope = null, lastCard = card())
        assertTrue(ConversationalReferentResolver.resolve("Explique melhor", ctx) is Referent.RequestClarification)
    }

    // ---- F4 — troca de nota com IDs de seção iguais ----

    @Test
    fun f4_trocaNota_mesmoIdDeSecao_pedeEsclarecimento() {
        // Mesmo ID de seção, nota diferente: o card antigo não é referente.
        val ctx = ctxCard(note = "n2", scope = "sec-1", cardNote = "n1", cardSection = "sec-1")
        val r = ConversationalReferentResolver.resolve("Explique melhor", ctx)
        assertTrue(r is Referent.RequestClarification)
    }

    // ---- F5 — referência explícita por identidade inequívoca ----

    @Test
    fun f5_ponto1_naoESelecionadoPorPonto10() {
        val c = card(targetLabel = "Ponto 1")
        val r = ConversationalReferentResolver.resolve("Explique melhor Ponto 10", ctxCard(c = c))
        assertTrue(r is Referent.RequestClarification)
        assertTrue(r !is Referent.DeepenCard)
    }

    // ---- F6 — conjugações de rascunho e pedido misto ----

    @Test
    fun f6_conjugacoesDeRascunho_prossegueFluxoNormal() {
        listOf(
            "Desenvolva o ponto 1", "Desenvolver o ponto 1",
            "Escreva um texto", "Escrever um texto",
            "Redija o ponto 1", "Redigir o ponto 1",
        ).forEach { req ->
            assertEquals("esperava ProceedNormal: $req", Referent.ProceedNormal, ConversationalReferentResolver.resolve(req, ctxCard()))
        }
    }

    @Test
    fun f6_pedidoMisto_explicacaoERedacao_respeitaIntencaoExplicita() {
        assertEquals(
            Referent.ProceedNormal,
            ConversationalReferentResolver.resolve("Explique melhor e escreva um rascunho", ctxCard()),
        )
    }
}
