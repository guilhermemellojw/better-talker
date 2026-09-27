package com.bettertalker.app

import com.bettertalker.app.data.copilot.ORATORY_STRUCTURE_RULES
import com.bettertalker.app.data.copilot.OratoryStructure
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.serializeOratoryStructure
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.s34.S34Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 20-A — estrutura oratória inferida.
 * Pura: sem Room, sem rede, sem LLM. Cobre os 22 itens de §24.
 */
class OratoryStructureTest {

    private val doc = S34Parser.parseS34(S34Fixture.TEXT)

    private fun inferred(current: String? = null): OratoryStructure.Inferred {
        val r = OratoryStructure.infer(doc, current)
        assertTrue(r is OratoryStructure.Result.Ok)
        return (r as OratoryStructure.Result.Ok).structure
    }

    // ---------- Modelo (§24.1-6) ----------

    @Test
    fun s34Com3PontosProduzAs3Partes() {
        val s = inferred()
        assertEquals(doc.id, s.outlineId)
        assertEquals(3, s.development.size)
        assertNotNull(s.introduction)
        assertNotNull(s.conclusion)
    }

    @Test
    fun introducaoApontaParaObjetivo() {
        val s = inferred()
        assertEquals(OratoryStructure.Source.S34, s.introduction.purpose.source)
        assertEquals(doc.objective, s.introduction.purpose.text)
    }

    @Test
    fun introducaoApontaParaPrimeiroPonto() {
        val s = inferred()
        assertEquals("sec-1", s.introduction.section!!.sectionId)
        assertEquals(1, s.introduction.section!!.order)
        assertEquals(OratoryStructure.Source.S34, s.introduction.section!!.source)
    }

    @Test
    fun desenvolvimentoContem123() {
        assertEquals(listOf(1, 2, 3), inferred().development.map { it.section.order })
    }

    @Test
    fun conclusaoApontaParaObjetivo() {
        val s = inferred()
        assertEquals(OratoryStructure.Source.S34, s.conclusion.purpose.source)
        assertEquals(doc.objective, s.conclusion.purpose.text)
    }

    @Test
    fun conclusaoApontaParaUltimoPonto() {
        val s = inferred()
        assertEquals("sec-3", s.conclusion.section!!.sectionId)
        assertEquals(3, s.conclusion.section!!.order)
    }

    // ---------- Conteúdo × treinamento (§24.7-9) ----------

    @Test
    fun fontesDeConteudoETreinamentoSaoDistintas() {
        for (part in listOf(inferred().introduction, inferred().conclusion)) {
            assertEquals(OratoryStructure.Source.S34, part.purpose.source)
            assertEquals(OratoryStructure.Source.S34, part.section!!.source)
            assertTrue(part.trainingSources.isNotEmpty())
            assertTrue(part.trainingSources.all { it.source == OratoryStructure.Source.TRAINING })
        }
    }

    @Test
    fun beThNaoApareceComoContentSource() {
        val s = inferred()
        val contentSources = listOfNotNull(s.introduction.purpose.source, s.introduction.section?.source,
            s.conclusion.purpose.source, s.conclusion.section?.source)
        assertTrue(contentSources.all { it == OratoryStructure.Source.S34 || it == OratoryStructure.Source.MISSING })
        assertFalse(contentSources.contains(OratoryStructure.Source.TRAINING))
    }

    @Test
    fun s34NaoApareceComoTreino() {
        val s = inferred()
        val training = s.introduction.trainingSources + s.conclusion.trainingSources
        assertTrue(training.all { it.source == OratoryStructure.Source.TRAINING })
    }

    @Test
    fun categoriasDeTreinoSaoDaTaxonomiaExistente() {
        val s = inferred()
        assertEquals(
            listOf(
                TrainingCategory.INTRODUCTION, TrainingCategory.QUESTIONS,
                TrainingCategory.CLARITY, TrainingCategory.NATURALNESS
            ),
            s.introduction.trainingSources.map { it.category }
        )
        assertEquals(
            listOf(
                TrainingCategory.CONCLUSION, TrainingCategory.APPLICATION,
                TrainingCategory.CLARITY, TrainingCategory.NATURALNESS
            ),
            s.conclusion.trainingSources.map { it.category }
        )
    }

    // ---------- Ordem (§24.10-12) ----------

    @Test
    fun desenvolvimentoPreserva123() {
        val ids = OratoryStructure.orderedSectionIds(inferred())
        assertEquals(listOf("sec-1", "sec-2", "sec-3"), ids)
    }

    @Test
    fun focoRespeitaOrdem() {
        val s = inferred("sec-2")
        assertEquals("sec-1", s.focus!!.previousSectionId)
        assertEquals("sec-2", s.focus!!.currentSectionId)
        assertEquals("sec-3", s.focus!!.nextSectionId)
    }

    @Test
    fun extremosDoFocoSaoNull() {
        assertEquals(null, inferred("sec-1").focus!!.previousSectionId)
        assertEquals(null, inferred("sec-3").focus!!.nextSectionId)
    }

    @Test
    fun nenhumScoreAlteraOrdem() {
        // A inferência não recebe score nenhum: só o documento.
        assertEquals(inferred().development, inferred().development)
        assertEquals(
            listOf(1, 2, 3),
            inferred("sec-3").development.map { it.section.order }
        )
    }

    // ---------- Ausência (§24.13-15) ----------

    @Test
    fun objectiveNullNaoGeraObjetivo() {
        val text = S34Fixture.TEXT.replace(
            "Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n", ""
        )
        val d = S34Parser.parseS34(text)
        val r = OratoryStructure.infer(d) as OratoryStructure.Result.Ok
        assertEquals(OratoryStructure.Source.MISSING, r.structure.introduction.purpose.source)
        assertNull(r.structure.introduction.purpose.text)
        // Estrutura continua existindo com os pontos disponíveis.
        assertEquals(3, r.structure.development.size)
    }

    @Test
    fun sectionsVaziasProduzemEstadoInsuficiente() {
        val vazio = S34Parser.parseS34("")
        assertEquals(
            OratoryStructure.Result.InsufficientStructure,
            OratoryStructure.infer(vazio)
        )
    }

    @Test
    fun noOutlineNaoCriaEstrutura() {
        // NoOutline é decidido antes: sem documento, nem se chama infer.
        val r = com.bettertalker.app.data.repo.S34StructuralRetriever.Result.NoOutline
        assertNull(OratoryStructure.currentSectionOf(r))
    }

    // ---------- Casos menores (§24.16-18) ----------

    private fun docComPontos(n: Int) = S34Parser.parseS34(
        "S-34\n\nTema: T\n\nObjetivo:\nObjetivo com detalhes suficientes para o teste.\n\n" +
            (1..n).joinToString("\n\n") {
                "$it. Ponto $it (2 min)\n   Leia João 17:$it.\n" +
                    "   Consulte a publicação de estudo w24.01, §$it."
            }
    )

    @Test
    fun umPontoFunciona() {
        val d = docComPontos(1)
        val s = (OratoryStructure.infer(d) as OratoryStructure.Result.Ok).structure
        assertEquals(1, s.development.size)
        assertEquals(s.development[0].section.sectionId, s.introduction.section!!.sectionId)
        assertEquals(s.development[0].section.sectionId, s.conclusion.section!!.sectionId)
        assertEquals(null, s.focus)
    }

    @Test
    fun doisPontosFuncionam() {
        val s = (OratoryStructure.infer(docComPontos(2)) as OratoryStructure.Result.Ok).structure
        assertEquals(2, s.development.size)
        assertEquals("sec-1", s.introduction.section!!.sectionId)
        assertEquals("sec-2", s.conclusion.section!!.sectionId)
    }

    @Test
    fun quatroOuMaisPontosFuncionam() {
        val s = (OratoryStructure.infer(docComPontos(5)) as OratoryStructure.Result.Ok).structure
        assertEquals(5, s.development.size)
        assertEquals("sec-1", s.introduction.section!!.sectionId)
        assertEquals("sec-5", s.conclusion.section!!.sectionId)
    }

    // ---------- Provenance (§24.19-22) ----------

    @Test
    fun provenanceCompleta() {
        val s = inferred("sec-2")
        assertEquals(OratoryStructure.Source.S34, s.introduction.purpose.source)
        assertEquals("sec-1", s.introduction.section!!.sectionId)
        assertEquals("sec-3", s.conclusion.section!!.sectionId)
        assertTrue(s.introduction.trainingSources.all { it.source == OratoryStructure.Source.TRAINING })
        assertEquals(2, s.development[1].section.order)
        assertTrue(s.development.all { it.section.source == OratoryStructure.Source.S34 })
    }

    // ---------- Mudança estrutural (§26) ----------

    @Test
    fun estruturaAcompanhaVersaoDoS34() {
        val v1 = (OratoryStructure.infer(docComPontos(3)) as OratoryStructure.Result.Ok).structure
        assertEquals(listOf(1, 2, 3), v1.development.map { it.section.order })
        assertEquals("sec-3", v1.conclusion.section!!.sectionId)

        val v2 = (OratoryStructure.infer(docComPontos(4)) as OratoryStructure.Result.Ok).structure
        assertEquals(listOf(1, 2, 3, 4), v2.development.map { it.section.order })
        assertEquals("sec-4", v2.conclusion.section!!.sectionId)
    }

    // ---------- Troca de ordem (§27) ----------

    @Test
    fun estruturaAcompanhaOrdemDocumental() {
        fun doc(titulos: List<String>) = S34Parser.parseS34(
            "S-34\n\nTema: T\n\nObjetivo:\nObjetivo com detalhes suficientes.\n\n" +
                titulos.mapIndexed { i, t ->
                    "${i + 1}. $t (2 min)\n   Corpo do ponto $t com detalhes.\n" +
                        "   Consulte a publicação de estudo w24.01, §${i + 1}."
                }.joinToString("\n\n")
        )
        val a = (OratoryStructure.infer(doc(listOf("A", "B", "C"))) as OratoryStructure.Result.Ok).structure
        assertEquals(listOf("A", "B", "C"), a.development.map { it.section.title })
        val b = (OratoryStructure.infer(doc(listOf("C", "A", "B"))) as OratoryStructure.Result.Ok).structure
        assertEquals(listOf("C", "A", "B"), b.development.map { it.section.title })
        assertEquals("C", b.introduction.section!!.title)
        assertEquals("B", b.conclusion.section!!.title)
    }

    // ---------- Foco ambíguo (§29) ----------

    @Test
    fun focoAusenteQuandoNaoResolvido() {
        assertNull(inferred(null).focus)
        assertNull(inferred("sec-99").focus)
        val unknown = com.bettertalker.app.data.repo.S34StructuralRetriever.Result.UnknownSection(
            doc.id, "sec-99"
        )
        assertNull(OratoryStructure.currentSectionOf(unknown))
        val unmatched = com.bettertalker.app.data.repo.S34StructuralRetriever.Result.UnmatchedSection(
            doc.id, "dica"
        )
        assertNull(OratoryStructure.currentSectionOf(unmatched))
    }

    // ---------- Serialização (§22/§23) ----------

    @Test
    fun serializacaoIdentificaEstruturaInferida() {
        val text = serializeOratoryStructure(inferred("sec-2"))
        assertTrue(text.contains("--- ESTRUTURA ORATÓRIA INFERIDA"))
        assertTrue(text.contains("INFERIDA A PARTIR DO S-34"))
        assertTrue(text.contains("não adiciona conteúdo factual"))
        assertTrue(text.contains("PARTE 1 — ABERTURA"))
        assertTrue(text.contains("DESENVOLVIMENTO — sequência exata do S-34"))
        assertTrue(text.contains("PARTE FINAL — CONCLUSÃO"))
        assertTrue(text.contains("FOCO ATUAL: sec-2"))
        assertTrue(text.contains("anterior: sec-1"))
        assertTrue(text.contains("próximo: sec-3"))
        assertTrue(text.contains("[TRAINING]"))
        assertFalse(text.contains("[TRAINING] S34"))
        // Não gera texto oratório.
        assertFalse(text.contains("Imagine"))
        assertFalse(text.contains("Para concluir"))
    }

    @Test
    fun promptTemBlocoERegraSoComEstrutura() {
        val s = inferred("sec-2")
        val com = buildChatPrompt(
            message = "como desenvolvimento?", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x", oratory = s
        )
        val sem = buildChatPrompt(
            message = "como desenvolvimento?", history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertTrue(com.contains("ESTRUTURA ORATÓRIA INFERIDA"))
        assertTrue(com.contains(ORATORY_STRUCTURE_RULES))
        assertFalse(sem.contains("ESTRUTURA ORATÓRIA INFERIDA"))
        assertFalse(sem.contains(ORATORY_STRUCTURE_RULES))
    }

    @Test
    fun regraDaEstruturaInferidaEcontrato() {
        // Normaliza quebras de linha: a regra é sobre o contrato, não sobre wrap.
        val r = ORATORY_STRUCTURE_RULES.lowercase().replace(Regex("\\s+"), " ")
        assertTrue(r.contains("derivada do s-34"))
        assertTrue(r.contains("não adiciona conteúdo factual"))
        assertTrue(r.contains("preserve a sequência"))
        assertTrue(r.contains("be/th somente para decidir como apresentar"))
        assertTrue(r.contains("nunca para inventar conteúdo factual"))
    }

    @Test
    fun naoCriaPontuacaoGlobal() {
        val fields = OratoryStructure.Inferred::class.java.declaredFields
            .filter { !it.isSynthetic }.map { it.name.lowercase() }
        assertFalse(fields.any { it.contains("score") })
        assertFalse(fields.any { it.contains("best") })
        assertFalse(fields.any { it.contains("winner") })
        assertFalse(fields.any { it.contains("rank") })
    }
}
