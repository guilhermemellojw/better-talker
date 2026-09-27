package com.bettertalker.app

import com.bettertalker.app.data.copilot.ChatRouter
import com.bettertalker.app.data.copilot.ChatRouter.Route
import com.bettertalker.app.data.copilot.ChatRouter.StructuralTopic
import com.bettertalker.app.data.copilot.OratoryGeneration.Action
import com.bettertalker.app.data.copilot.OratoryGeneration.Mode
import com.bettertalker.app.data.copilot.OratorySession
import com.bettertalker.app.data.s34.S34Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 20-D — roteamento natural do chat (§§38-48).
 * Puro: sem LLM, rede, banco. Cobre comandos, iteração, perguntas,
 * falsos positivos, ausência de contexto e troca de modo/ponto.
 */
class ChatRouterTest {

    private val doc = S34Parser.parseS34(S34Fixture.TEXT)

    private fun route(
        text: String,
        document: com.bettertalker.app.data.s34.S34Document? = doc,
        current: String? = "sec-2",
        last: OratorySession.LastGeneration? = null
    ) = ChatRouter.route(text, document, current, last)

    private fun oratory(r: Route): Route.Oratory {
        assertTrue("esperado ORATORY, veio ${ChatRouter.describe(r)}", r is Route.Oratory)
        return r as Route.Oratory
    }

    // ---------- §38: comandos de geração ----------

    @Test
    fun comandosDeIntroducao() {
        for (t in listOf("Crie uma introdução.", "Faça uma abertura.", "Como começo esse discurso?")) {
            val r = oratory(route(t))
            assertEquals(t, Mode.INTRODUCTION, r.mode)
            assertEquals(t, "sec-1", r.sectionId)
        }
    }

    @Test
    fun comandosDeDesenvolvimento() {
        assertEquals("sec-2", oratory(route("Desenvolva o ponto 2.")).sectionId)
        val atual = oratory(route("Me ajude a desenvolver esse ponto."))
        assertEquals(Mode.DEVELOPMENT, atual.mode)
        assertEquals("sec-2", atual.sectionId)
        val explicar = oratory(route("Explique melhor o ponto 2."))
        assertEquals(Mode.DEVELOPMENT, explicar.mode)
        assertEquals("sec-2", explicar.sectionId)
    }

    @Test
    fun comandoDeTransicaoParaOPonto3() {
        val r = oratory(route("Faça uma transição para o ponto 3."))
        assertEquals(Mode.TRANSITION, r.mode)
        assertEquals("sec-3", r.sectionId)
    }

    @Test
    fun comandoDeTransicaoNatural() {
        val r = oratory(route("Como passo para o próximo ponto?"))
        assertEquals(Mode.TRANSITION, r.mode)
        assertEquals("sec-2", r.sectionId)
    }

    @Test
    fun comandosDeConclusao() {
        for (t in listOf("Faça uma conclusão.", "Como posso concluir?")) {
            val r = oratory(route(t))
            assertEquals(t, Mode.CONCLUSION, r.mode)
            assertEquals(t, "sec-3", r.sectionId)
        }
    }

    // ---------- §39: iteração ----------

    @Test
    fun iteracaoPermaneceNaIntroducao() {
        val last = OratorySession.LastGeneration(Mode.INTRODUCTION, "sec-1")
        for (t in listOf("Melhore.", "Deixe mais natural.", "Encurte.")) {
            val r = oratory(route(t, last = last))
            assertEquals(t, Mode.INTRODUCTION, r.mode)
            assertEquals(t, "sec-1", r.sectionId)
            assertTrue(t, r.inherited)
        }
    }

    @Test
    fun explicarMelhorMantemOMesmoPonto() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val r = oratory(route("Explique melhor.", last = last))
        assertEquals(Mode.DEVELOPMENT, r.mode)
        assertEquals("sec-2", r.sectionId)
    }

    @Test
    fun melhoreIssoUsaOAlvoAnterior() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val r = oratory(route("Melhore isso.", last = last))
        assertEquals("sec-2", r.sectionId)
        assertEquals(Action.REPLACE, r.action)
    }

    @Test
    fun trocaDeModoEmCadeia() {
        val intro = oratory(route("Crie uma introdução.", current = "sec-2"))
        assertEquals(Mode.INTRODUCTION, intro.mode)
        val dev = oratory(route("Agora desenvolva o ponto 2.", current = "sec-1",
            last = OratorySession.LastGeneration(intro.mode, intro.sectionId)))
        assertEquals(Mode.DEVELOPMENT, dev.mode)
        assertEquals("sec-2", dev.sectionId)
        assertFalse(dev.inherited)
    }

    @Test
    fun trocaDePontoAtualizaASecao() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val r = oratory(route("Agora desenvolva o ponto 3.", last = last))
        assertEquals(Mode.DEVELOPMENT, r.mode)
        assertEquals("sec-3", r.sectionId)
    }

    // ---------- §40: perguntas estruturais ----------

    @Test
    fun perguntasEstruturaisNaoGeramProposta() {
        val casos = mapOf(
            "Qual é o objetivo desse discurso?" to StructuralTopic.OBJECTIVE,
            "Quais são os pontos principais?" to StructuralTopic.POINTS,
            "Quais textos estão ligados ao ponto 2?" to StructuralTopic.REFERENCES,
            "Qual publicação está ligada ao ponto 3?" to StructuralTopic.REFERENCES
        )
        for ((texto, topico) in casos) {
            val r = route(texto)
            assertTrue(texto, r is Route.StructuralQuery)
            assertEquals(texto, topico, (r as Route.StructuralQuery).topic)
            assertFalse(texto, r is Route.Oratory)
        }
    }

    @Test
    fun perguntaDeSequenciaEreconhecida() {
        val r = route("Qual é a sequência dos pontos?")
        assertTrue(r is Route.StructuralQuery)
        assertEquals(StructuralTopic.SEQUENCE, (r as Route.StructuralQuery).topic)
    }

    // ---------- §41: falsos positivos evitados ----------

    @Test
    fun perguntasSobreOPapelDaIntroducaoNaoGeram() {
        for (t in listOf(
            "O que o S-34 diz sobre a introdução?",
            "Qual é a conclusão do esboço?",
            "Existe uma introdução nesse esboço?"
        )) {
            val r = route(t)
            assertFalse("$t não pode ser ORATORY", r is Route.Oratory)
            assertTrue("$t deve ser consulta ou geral", r is Route.StructuralQuery || r is Route.General)
        }
    }

    @Test
    fun explicarOTermoNaoEgeracao() {
        val r = route("Explique o termo desenvolvimento.")
        assertFalse(r is Route.Oratory)
        assertFalse(r is Route.StructuralQuery)
        assertTrue(r is Route.General)
    }

    @Test
    fun comoImportarArquivoNaoEoratorio() {
        val r = route("Como posso importar um arquivo?")
        assertFalse(r is Route.Oratory)
    }

    // ---------- §42: ausência de contexto ----------

    @Test
    fun refinamentoSemPropostaNaoInventaAlvo() {
        val r = route("Melhore.")
        assertTrue(r is Route.NothingToRefine)
        assertTrue((r as Route.NothingToRefine).message.contains("Ainda não gerei"))
    }

    @Test
    fun desenvolverIssoSemPontoNaoInventa() {
        // current=null e sem sessão → o pedido explícito de desenvolvimento
        // segue para a sessão, que devolve alvo nulo (a geração bloqueia).
        val r = route("Desenvolva isso.", current = null)
        assertTrue(r is Route.Oratory)
        assertEquals(null, (r as Route.Oratory).sectionId)
    }

    @Test
    fun transicaoSemSeguinteNaoInventa() {
        val last = OratorySession.LastGeneration(Mode.TRANSITION, "sec-2")
        // "para o próximo" ancora no ponto atual: sec-2 → sec-3 é válido.
        val valida = oratory(route("Faça uma transição para o próximo.", current = "sec-2", last = last))
        assertEquals("sec-2", valida.sectionId)
        // No ÚLTIMO ponto não há próximo: a geração devolve estado explícito.
        val r = oratory(route("Faça uma transição para o próximo.", current = "sec-3", last = last))
        assertEquals("sec-3", r.sectionId)
        val v = com.bettertalker.app.data.s34.S34StructuralRetrieval.scopeToSection(doc, "sec-3")!!
        val g = com.bettertalker.app.data.copilot.OratoryGeneration.spec(
            Mode.TRANSITION, doc, v, Action.INSERT
        )
        assertTrue(g is com.bettertalker.app.data.copilot.OratoryGeneration.Result.CannotGenerate)
        assertTrue((g as com.bettertalker.app.data.copilot.OratoryGeneration.Result.CannotGenerate).blocked
            is com.bettertalker.app.data.copilot.OratoryGeneration.Blocked.NoNextSection)
    }

    @Test
    fun semS34RoteiaEbloqueiaComEstadoHonesto() {
        // Sem estrutura, a rota é oratória e a GERAÇÃO devolve estado
        // explícito (nunca inventa estrutura nem finge sucesso).
        val r = oratory(route("Crie uma introdução.", document = null, current = null))
        assertEquals(Mode.INTRODUCTION, r.mode)
        assertEquals(null, r.sectionId)
        val vazio = S34Parser.parseS34("")
        val g = com.bettertalker.app.data.copilot.OratoryGeneration.spec(
            r.mode, vazio, null, r.action
        )
        assertTrue(g is com.bettertalker.app.data.copilot.OratoryGeneration.Result.CannotGenerate)
        assertEquals(
            com.bettertalker.app.data.copilot.OratoryGeneration.Blocked.NoStructure,
            (g as com.bettertalker.app.data.copilot.OratoryGeneration.Result.CannotGenerate).blocked
        )
    }

    // ---------- §27-28: réplica de proposta ----------

    @Test
    fun aceitarERejeitarVaoparaOFluxoExistente() {
        val aceitar = route("Aceitar")
        assertTrue(aceitar is Route.ProposalReply)
        assertTrue((aceitar as Route.ProposalReply).accept)
        val rejeitar = route("Rejeitar")
        assertTrue(rejeitar is Route.ProposalReply)
        assertFalse((rejeitar as Route.ProposalReply).accept)
    }

    // ---------- §34: fora do escopo estrutural ----------

    @Test
    fun criarPonto4Erecusado() {
        val r = route("Crie um ponto 4 para este discurso.")
        assertTrue(r is Route.OutOfScope)
        assertEquals(4, (r as Route.OutOfScope).requestedPoint)
        assertEquals(3, doc.sections.size)
    }

    // ---------- §26: chat geral continua ----------

    @Test
    fun chatGeralContinuaExistindo() {
        for (t in listOf("Bom dia!", "Obrigado pela ajuda.", "Me explique esse assunto.")) {
            assertTrue("$t", route(t) is Route.General)
        }
    }

    // ---------- §35: sem score ----------

    @Test
    fun rotaNaoTemScore() {
        val names = mutableSetOf<String>()
        fun collect(o: Any?) {
            if (o == null) return
            o.javaClass.declaredFields.forEach { f ->
                names += f.name.lowercase()
                f.isAccessible = true
                val v = runCatching { f.get(o) }.getOrNull()
                if (v is List<*>) v.forEach { collect(it) }
            }
        }
        collect(route("Desenvolva o ponto 2."))
        assertFalse(names.any { it.contains("score") })
        assertFalse(names.any { it.contains("confidence") })
    }

    // ---------- §49: rota → spec → request (provider fake) ----------

    @Test
    fun rotaOratoriaChegaAoRequestComPromptCorreto() {
        val r = oratory(route("Desenvolva o ponto 2."))
        lastOratory = OratorySession.LastGeneration(r.mode, r.sectionId)
        val v = com.bettertalker.app.data.s34.S34StructuralRetrieval.scopeToSection(doc, r.sectionId!!)!!
        val spec = (com.bettertalker.app.data.copilot.OratoryGeneration.spec(
            r.mode, doc, v, r.action
        ) as com.bettertalker.app.data.copilot.OratoryGeneration.Result.Ready).spec
        val http = LlmProviderTestSupport.okHttp()
        val provider = com.bettertalker.app.data.llm.GeminiProvider(apiKey = "k", http = http, log = {})
        kotlinx.coroutines.runBlocking {
            provider.generate(com.bettertalker.app.data.llm.LlmRequest(
                text = "corpo", message = "Desenvolva o ponto 2.",
                oratorySpec = spec
            ))
        }
        val body = http.bodies.first()
        assertTrue(body.contains("MODO: DESENVOLVIMENTO DO PONTO"))
        assertTrue(body.contains("ESTRUTURA DO S-34"))
        assertTrue(body.contains("Tiago 2:17"))
        assertFalse(body.contains("Hebreus 10:23"))
        // §46: as fontes não mudaram com o roteamento.
        assertTrue(body.contains("[TRAINING] development"))
    }

    private var lastOratory: OratorySession.LastGeneration? = null

    // ---------- §52: diagnóstico ----------

    @Test
    fun diagnosticoTemRotaModoESecaoSemTextoPrivado() {
        val d = ChatRouter.describe(oratory(route("Desenvolva o ponto 2.")))
        assertTrue(d.contains("route=ORATORY"))
        assertTrue(d.contains("mode=DEVELOPMENT"))
        assertTrue(d.contains("section=sec-2"))
        assertFalse(d.contains(doc.objective.orEmpty()))
    }
}
