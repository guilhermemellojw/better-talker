package com.bettertalker.app.data.copilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PEDAGOGY-0A / P3 — cenários adversariais do resolvedor de referente
 * conversacional. Deterministas, sem LLM/rede.
 */
class ConversationalReferentResolverTest {

    private fun card(targetId: String? = "sec-1", targetLabel: String = "Romanos 8") =
        CardPayload(
            targetKind = CardTargetKind.SECTION,
            targetId = targetId,
            targetLabel = targetLabel,
        )

    private fun suggestion(label: String = "Desenvolva o ponto 1") =
        NextStepSuggestion(
            label = label,
            detail = "",
            action = ResolvedChatNav.DevelopCurrent(sectionId = "sec-1", title = "Ponto 1"),
        )

    // ---- Cenário A: "Explique melhor" após um card ----

    @Test
    fun a_expliqueMelhor_aposCard_aprofundaCard() {
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = card(), lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Explique melhor", ctx))
    }

    // ---- Cenário B: "Explique melhor" após uma sugestão ----

    @Test
    fun b_expliqueMelhor_aposSugestao_explicaSugestao() {
        val s = suggestion()
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = null, lastSuggestion = s, lastResponse = null,
        )
        assertEquals(Referent.ExplainSuggestion(s), ConversationalReferentResolver.resolve("Explique melhor", ctx))
    }

    // ---- Cenário C: "Explique melhor" sem referente inequívoco ----

    @Test
    fun c_expliqueMelhor_semReferente_pedeEsclarecimento() {
        val ctx = Context(noteId = "n1", chatScope = null, lastCard = null, lastSuggestion = null, lastResponse = null)
        val r = ConversationalReferentResolver.resolve("Explique melhor", ctx)
        assertEquals(true, r is Referent.RequestClarification)
    }

    // ---- Cenário D: "Não entendi" após uma explicação ----

    @Test
    fun d_naoEntendi_aposExplicacao_aprofundaCard() {
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = card(), lastSuggestion = null,
            lastResponse = "Então o ponto é ...",
        )
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Não entendi", ctx))
    }

    // ---- Cenário E: "Dá outro exemplo" preservando o assunto ----

    @Test
    fun e_daoOutroExemplo_preservaAssunto_aprofundaCard() {
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = card(), lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Dá outro exemplo", ctx))
    }

    // ---- Cenário F: troca da seção A para B ----

    @Test
    fun f_trocaSecao_A_para_B_explicaTópicoAtivo() {
        // Card antigo do tópico A; usuário passou ao tópico B (chatScope = sec-2).
        val ctx = Context(
            noteId = "n1", chatScope = "sec-2",
            lastCard = card(targetId = "sec-1"), lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.ExplainActiveSection("sec-2"), ConversationalReferentResolver.resolve("Explique melhor", ctx))
    }

    // ---- Cenário G: troca de nota ----

    @Test
    fun g_trocaNota_comSecaoAtiva_explicaSecaoAtiva() {
        // Nota nova (noteId = n2), card do notes anterior, seção ativa = sec-5.
        val ctx = Context(
            noteId = "n2", chatScope = "sec-5",
            lastCard = card(targetId = "sec-1"), lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.ExplainActiveSection("sec-5"), ConversationalReferentResolver.resolve("Explique melhor", ctx))
    }

    @Test
    fun g2_trocaNota_semSecaoAtiva_pedeEsclarecimento() {
        val ctx = Context(noteId = "n2", chatScope = null, lastCard = card(targetId = "sec-1"), lastResponse = null)
        val r = ConversationalReferentResolver.resolve("Explique melhor", ctx)
        assertEquals(true, r is Referent.RequestClarification)
    }

    // ---- Cenário H: mensagem mista — "Explique melhor Romanos 8" (referência explícita) ----

    @Test
    fun h_explicarMelhorRomanos8_casaCard_aprofundaCard() {
        val c = card(targetId = "sec-1", targetLabel = "Romanos 8")
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = c, lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.DeepenCard(c), ConversationalReferentResolver.resolve("Explique melhor Romanos 8", ctx))
    }

    // ---- Cenário I: conversa retomada após interrupção ----

    @Test
    fun i_retomadaAposInterrupcao_resumeReferente() {
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = card(), lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Vamos continuar de onde paramos", ctx))
    }

    // ---- Cenário J: último referente removido ou indisponível ----

    @Test
    fun j_referenteRemovido_pedeEsclarecimento() {
        val ctx = Context(noteId = "n1", chatScope = null, lastCard = null, lastSuggestion = null, lastResponse = null)
        val r = ConversationalReferentResolver.resolve("Explique melhor", ctx)
        assertEquals(true, r is Referent.RequestClarification)
    }

    // ---- Cenário K: ausência de fontes documentais (corpus factual vazio) ----

    @Test
    fun k_ausenciaFontesDocumentais_resolveReferente() {
        // Sem evidência documental: o resolvedor resolve o referente — DeepenCard —
        // sem declarar verificação.
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = card(), lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.DeepenCard(card()), ConversationalReferentResolver.resolve("Explique melhor", ctx))
    }

    // ---- Cenário L: pedido explícito de produzir um rascunho ----

    @Test
    fun l_pedidoExplicitoRascunho_prossegueFluxoNormal() {
        val ctx = Context(
            noteId = "n1", chatScope = "sec-1",
            lastCard = card(), lastSuggestion = null, lastResponse = null,
        )
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Desenvolva o ponto 1", ctx))
    }

    // ---- P4: não presume que toda mensagem curta é solicitação simples ----

    @Test
    fun p4_mensagensCurtasSemVerboDeAprofundamento_prossegueFluxoNormal() {
        val ctx = Context(noteId = "n1", chatScope = null, lastCard = null, lastSuggestion = null, lastResponse = null)
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Ok", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Sim", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Entendi", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Qual a próxima?", ctx))
        assertEquals(Referent.ProceedNormal, ConversationalReferentResolver.resolve("Mensagem curta sem verbo", ctx))
    }
}
