package com.bettertalker.app

import com.bettertalker.app.data.copilot.OratoryGeneration
import com.bettertalker.app.data.copilot.OratoryGeneration.Action
import com.bettertalker.app.data.copilot.OratoryGeneration.Mode
import com.bettertalker.app.data.copilot.OratoryGeneration.Result
import com.bettertalker.app.data.copilot.OratorySession
import com.bettertalker.app.data.copilot.OratorySession.Decision
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34StructuralRetrieval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 20-C — continuidade da geração (§§16-20, 31-34):
 * iteração herda modo+ponto, troca de modo/ponto muda o alvo, pedido
 * estrutural é recusado. Puro: sem LLM/rede/banco.
 */
class OratorySessionTest {

    private val doc = S34Parser.parseS34(S34Fixture.TEXT)
    private val view2 = S34StructuralRetrieval.scopeToSection(doc, "sec-2")!!
    private val view3 = S34StructuralRetrieval.scopeToSection(doc, "sec-3")!!

    private fun decide(text: String, current: String? = "sec-2", last: OratorySession.LastGeneration? = null) =
        OratorySession.decide(text, doc, current, last)

    private fun generated(d: Decision): Decision.Generate {
        assertTrue("esperado Generate, veio $d", d is Decision.Generate)
        return d as Decision.Generate
    }

    // ---------- Pedido explícito ----------

    @Test
    fun pedidoExplicitoTemPrecedencia() {
        val d = generated(decide("Crie uma introdução para este discurso"))
        assertEquals(Mode.INTRODUCTION, d.mode)
        assertEquals("sec-1", d.sectionId)
        assertEquals(Action.INSERT, d.action)
        assertFalse(d.inherited)
    }

    @Test
    fun pontoExplicitoEscolheASecaoCerta() {
        val d = generated(decide("Desenvolva o ponto 3"))
        assertEquals(Mode.DEVELOPMENT, d.mode)
        assertEquals("sec-3", d.sectionId)
    }

    @Test
    fun pedidoSemPontoUsaOFocoAtual() {
        val d = generated(decide("Desenvolva este ponto", current = "sec-2"))
        assertEquals("sec-2", d.sectionId)
    }

    // ---------- Iteração: herda modo + ponto (§§16-20) ----------

    @Test
    fun melhoreHerdaModoEPonto() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val d = generated(decide("Melhore.", last = last))
        assertEquals(Mode.DEVELOPMENT, d.mode)
        assertEquals("sec-2", d.sectionId)
        assertEquals(Action.REPLACE, d.action)
        assertTrue(d.inherited)
    }

    @Test
    fun deixeMaisNaturalMantemOPonto() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val d = generated(decide("Deixe mais natural.", last = last))
        assertEquals(Mode.DEVELOPMENT, d.mode)
        assertEquals("sec-2", d.sectionId)
        assertTrue(d.inherited)
    }

    @Test
    fun encurteMantemOPonto() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val d = generated(decide("Encurte.", last = last))
        assertEquals(Mode.DEVELOPMENT, d.mode)
        assertEquals("sec-2", d.sectionId)
    }

    @Test
    fun expliqueMelhorMantemOMesmoPontoSemPuxarOProximo() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val d = generated(decide("Explique melhor.", last = last))
        assertEquals("sec-2", d.sectionId)
        assertFalse(d.sectionId == "sec-3")
    }

    @Test
    fun iteracaoDaIntroducaoContinuaIntroducao() {
        val last = OratorySession.LastGeneration(Mode.INTRODUCTION, "sec-1")
        for (t in listOf("Melhore.", "Deixe mais natural.", "Encurte.")) {
            val d = generated(decide(t, last = last))
            assertEquals(t, Mode.INTRODUCTION, d.mode)
            assertEquals("sec-1", d.sectionId)
        }
    }

    // ---------- Mudança de modo/ponto (§§31-32) ----------

    @Test
    fun mudancaDeModoSubstituiAHeranca() {
        val last = OratorySession.LastGeneration(Mode.INTRODUCTION, "sec-1")
        val d = generated(decide("Agora desenvolva o ponto 2", last = last))
        assertEquals(Mode.DEVELOPMENT, d.mode)
        assertEquals("sec-2", d.sectionId)
        assertFalse(d.inherited)
    }

    @Test
    fun mudancaDePontoAcompanhaAReferencia() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val d = generated(decide("Agora desenvolva o ponto 3", last = last))
        assertEquals(Mode.DEVELOPMENT, d.mode)
        assertEquals("sec-3", d.sectionId)
        // A geração passa a usar as referências do ponto 3.
        val spec = (OratoryGeneration.spec(d.mode, doc, view3, d.action) as Result.Ready).spec
        val labels = spec.current!!.references.map { it.label }
        assertTrue(labels.any { it.contains("Hebreus 10:23") })
        assertFalse(labels.any { it.contains("Tiago 2:17") })
    }

    // ---------- Ambíguo e fora de escopo (§§33-34) ----------

    @Test
    fun refinamentoSemGeracaoAnteriorNaoInventaAlvo() {
        val d = decide("Melhore.")
        assertTrue(d is Decision.NothingToRefine)
        assertTrue((d as Decision.NothingToRefine).message.contains("Ainda não gerei"))
    }

    @Test
    fun pedidoDesconhecidoNaoViraGeracao() {
        assertTrue(decide("Qual é o objetivo?") is Decision.NothingToRefine)
    }

    @Test
    fun criarPonto4ErecusadoSemAlterarEstrutura() {
        val d = decide("Crie um ponto 4 para este discurso")
        assertTrue(d is Decision.OutOfStructuralScope)
        assertEquals(4, (d as Decision.OutOfStructuralScope).requestedPoint)
        assertTrue(d.message.contains("estrutura vem do S-34"))
        // E a estrutura permanece intacta.
        assertEquals(3, doc.sections.size)
        assertEquals(listOf(1, 2, 3), doc.sections.map { it.order })
    }

    @Test
    fun criarPontoExistenteNaoEforaDeEscopo() {
        // "Adicione um ponto 2" não é criação de estrutura nova (o ponto existe):
        // cai no fluxo normal de modo/detecção.
        val d = OratorySession.decide("Adicione conteúdo ao ponto 2", doc, "sec-2", null)
        assertFalse(d is Decision.OutOfStructuralScope)
    }

    // ---------- Iteração mantém as fontes estruturais ----------

    @Test
    fun iteracaoMantemObjetivoEPrimeiroPonto() {
        val last = OratorySession.LastGeneration(Mode.INTRODUCTION, "sec-1")
        val d = generated(decide("Deixe mais natural.", last = last))
        val spec = (OratoryGeneration.spec(
            d.mode, doc, S34StructuralRetrieval.scopeToSection(doc, d.sectionId!!)!!, d.action
        ) as Result.Ready).spec
        assertEquals(doc.objective, spec.objective)
        assertEquals("sec-1", spec.current!!.sectionId)
        assertEquals(listOf(1, 2, 3), spec.orderedSections.map { it.order })
    }

    @Test
    fun iteracaoDoDesenvolvimentoPreservaSubpontos() {
        val last = OratorySession.LastGeneration(Mode.DEVELOPMENT, "sec-2")
        val d = generated(decide("Encurte.", last = last))
        val spec = (OratoryGeneration.spec(d.mode, doc, view2, d.action) as Result.Ready).spec
        assertEquals(2, spec.current!!.subsections.size)
        assertTrue(spec.current!!.references.any { it.label.contains("Tiago 2:17") })
        // O ponto seguinte NÃO entra no desenvolvimento (§8).
        val prompt = OratoryGeneration.buildPrompt(spec, "Encurte.")
        assertFalse(prompt.contains("Hebreus 10:23"))
        assertTrue(prompt.contains("MODO: DESENVOLVIMENTO DO PONTO"))
    }

    // ---------- F20-E: âncora da transição (2→3) ----------

    @Test
    fun destinoDaTransicaoAncoraNaOrigem() {
        val destino = generated(OratorySession.decide("Faça uma transição para o ponto 3.", doc, "sec-1", null))
        assertEquals(Mode.TRANSITION, destino.mode)
        assertEquals("sec-2", destino.sectionId)
        val origem = generated(OratorySession.decide("Crie uma transição do ponto 2 para o 3", doc, "sec-1", null))
        assertEquals("sec-2", origem.sectionId)
        // Destino sem origem possível (ponto 1) → âncora nula (bloqueio honesto).
        val impossivel = generated(OratorySession.decide("Faça uma transição para o ponto 1", doc, "sec-3", null))
        assertEquals(null, impossivel.sectionId)
    }

    // ---------- Sequência completa (§44) ----------

    @Test
    fun sequenciaCompletaMantemContinuidade() {
        val passos = listOf(
            Triple("Crie uma introdução", Mode.INTRODUCTION, "sec-1"),
            Triple("Desenvolva o ponto 1", Mode.DEVELOPMENT, "sec-1"),
            Triple("Crie uma transição do ponto 1 para o 2", Mode.TRANSITION, "sec-1"),
            Triple("Desenvolva o ponto 2", Mode.DEVELOPMENT, "sec-2"),
            Triple("Crie uma transição do ponto 2 para o 3", Mode.TRANSITION, "sec-2"),
            Triple("Desenvolva o ponto 3", Mode.DEVELOPMENT, "sec-3"),
            Triple("Crie uma conclusão", Mode.CONCLUSION, "sec-3")
        )
        var last: OratorySession.LastGeneration? = null
        for ((texto, modo, secao) in passos) {
            val d = generated(OratorySession.decide(texto, doc, null, last))
            assertEquals(texto, modo, d.mode)
            assertEquals(texto, secao, d.sectionId)
            // Cada etapa pode ser montada sem erro.
            val view = S34StructuralRetrieval.scopeToSection(doc, d.sectionId!!)
            val r = OratoryGeneration.spec(d.mode, doc, view, d.action)
            assertTrue("$texto deveria gerar", r is Result.Ready)
            last = OratorySession.remember(d, doc)
        }
        assertEquals(Mode.CONCLUSION, last!!.mode)
    }
}
