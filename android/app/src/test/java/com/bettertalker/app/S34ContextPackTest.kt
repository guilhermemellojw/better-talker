package com.bettertalker.app

import com.bettertalker.app.data.copilot.S34_PROMPT_RULES
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.serializePack
import com.bettertalker.app.data.copilot.serializeStructuralContext
import com.bettertalker.app.data.copilot.structuralContextOf
import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.repo.S34OutlineRepository
import com.bettertalker.app.data.repo.S34StructuralRetriever
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34RefType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 19-B.5 — ContextPack estruturado + cláusula S-34 no prompt.
 * O teste principal (§28) percorre:
 * fixture → parser → persistência → retrieval estrutural → contexto → prompt.
 */
class S34ContextPackTest {

    private class FakeS34Dao : S34Dao {
        val outlines = mutableMapOf<String, S34OutlineEntity>()
        val sections = mutableMapOf<String, S34SectionEntity>()
        val subs = mutableMapOf<String, S34SubsectionEntity>()
        val refs = mutableMapOf<String, S34ReferenceEntity>()
        override suspend fun putOutline(o: S34OutlineEntity) { outlines[o.id] = o }
        override suspend fun putSections(list: List<S34SectionEntity>) {
            list.forEach { sections[it.id] = it }
        }
        override suspend fun putSubsections(list: List<S34SubsectionEntity>) {
            list.forEach { subs[it.id] = it }
        }
        override suspend fun putReferences(list: List<S34ReferenceEntity>) {
            list.forEach { refs[it.id] = it }
        }
        override suspend fun outlineById(id: String) = outlines[id]
        override suspend fun outlineBySource(sourceId: String) =
            outlines.values.firstOrNull { it.sourceAttachmentId == sourceId }
        override suspend fun sectionsOf(outlineId: String) =
            sections.values.filter { it.outlineId == outlineId }.sortedBy { it.order }
        override suspend fun subsectionsOf(sectionIds: List<String>) =
            subs.values.filter { it.sectionId in sectionIds }
                .sortedWith(compareBy({ it.sectionId }, { it.order }))
        override suspend fun referencesOf(outlineId: String) =
            refs.values.filter { it.outlineId == outlineId }.sortedBy { it.order }
        override suspend fun deleteRefsOf(outlineId: String) {
            refs.entries.removeIf { it.value.outlineId == outlineId }
        }
        override suspend fun deleteSubsOf(sectionIds: List<String>) {
            subs.entries.removeIf { it.value.sectionId in sectionIds }
        }
        override suspend fun deleteSectionsOf(outlineId: String) {
            sections.entries.removeIf { it.value.outlineId == outlineId }
        }
        override suspend fun deleteOutline(id: String) { outlines.remove(id) }
        override suspend fun deleteOutlineBySource(sourceId: String) {
            outlines.entries.removeIf { it.value.sourceAttachmentId == sourceId }
        }
    }

    private lateinit var repo: S34OutlineRepository
    private lateinit var retriever: S34StructuralRetriever

    private val trainingSource = EvidenceSource(
        id = "t1", reference = "Beneficie-se lição 5", text = "fale com contato visual",
        sourceType = SourceType.TRAINING, publication = "be", section = null,
        paragraph = null, page = null, trainingCategory = TrainingCategory.DELIVERY
    )

    @org.junit.Before
    fun setup() {
        val dao = FakeS34Dao()
        repo = S34OutlineRepository(dao)
        retriever = S34StructuralRetriever(repo)
    }

    private fun contextFor(
        source: String,
        sectionHint: String? = null,
        sectionId: String? = null,
        query: String = ""
    ) = runBlocking {
        val r = retriever.retrieve(source, sectionId = sectionId, sectionHint = sectionHint, query = query)
        val doc = repo.getBySource(source)
        structuralContextOf(r, doc)
    }

    // ---------- Estrutura (§25.1-7) ----------

    @Test
    fun contextoEstruturalTemIdTituloObjetivoEOrdem() {
        runBlocking { repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1") }
        val ctx = contextFor("att-1", sectionHint = "colocamos em prática")!!
        assertEquals("s34-" + com.bettertalker.app.data.edit.hashText(S34Fixture.TEXT), ctx.outlineId)
        assertEquals("Como fortalecer a fé", ctx.title)
        assertEquals(
            "Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.",
            ctx.objective
        )
        assertEquals(listOf(1, 2, 3), ctx.orderedSections.map { it.order })
        assertEquals(
            listOf(
                "A fé precisa de uma base sólida",
                "A fé cresce quando colocamos em prática o que aprendemos",
                "Continue fortalecendo sua fé"
            ),
            ctx.orderedSections.map { it.title }
        )
    }

    @Test
    fun pontoAtualComSubpontosEReferencias() {
        runBlocking { repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1") }
        val ctx = contextFor("att-1", sectionHint = "colocamos em prática")!!
        val cur = ctx.currentSection!!
        assertEquals("sec-2", cur.id)
        assertEquals(2, cur.order)
        assertEquals(2, cur.subsections.size)
        assertEquals(listOf(1, 2), cur.subsections.map { it.order })
        assertTrue(cur.references.any { it.rawText.contains("Tiago") })
    }

    // ---------- Referências (§25.10-12) ----------

    @Test
    fun referenciasPertencemAoPontoCerto() {
        runBlocking { repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1") }
        val ctx = contextFor("att-1", sectionHint = "colocamos em prática")!!
        val refs = ctx.currentSection!!.references
        assertTrue(refs.any { it.rawText.contains("Tiago 2:17") && it.type == S34RefType.BIBLE })
        assertTrue(refs.any { it.rawText.contains("w24.02") && it.type == S34RefType.PUBLICATION })
        // Nada do ponto 1 ou 3.
        assertFalse(refs.any { it.rawText.contains("João 17:17") })
        assertFalse(refs.any { it.rawText.contains("Hebreus") })
        assertFalse(refs.any { it.rawText.contains("w24.01") })
        // Dono explícito.
        assertTrue(refs.all { it.ownerId == "sec-2" || it.ownerId.startsWith("sec-2-") })
    }

    @Test
    fun referenciaDeSubpontoApareceNoSubponto() {
        val text = S34Fixture.TEXT.replace(
            "   b) Aplicar o que aprendemos",
            "   b) Aplicar o que aprendemos. Leia João 3:16."
        )
        runBlocking { repo.save(S34Parser.parseS34(text), "att-1") }
        val ctx = contextFor("att-1", sectionHint = "colocamos em prática")!!
        val sub2 = ctx.currentSection!!.subsections[1]
        assertTrue(sub2.references.any { it.rawText.contains("João 3:16") })
        assertFalse(ctx.currentSection!!.references.any { it.rawText.contains("João 3:16") })
    }

    // ---------- Isolamento (§25.13-14) ----------

    @Test
    fun naoMisturaOutlinesNemPontoErrado() = runBlocking {
        val a = S34Parser.parseS34(
            "S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A detalhado o suficiente.\n\n" +
                "1. CONFIANÇA (2 min)\n   Conteúdo A de confiança.\n\n" +
                "2. ORAÇÃO (2 min)\n   Conteúdo A de oração."
        )
        val b = S34Parser.parseS34(
            "S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B detalhado o suficiente.\n\n" +
                "1. CONFIANÇA (2 min)\n   Conteúdo B de confiança.\n\n" +
                "2. ESPERANÇA (2 min)\n   Conteúdo B de esperança."
        )
        repo.save(a, "att-A")
        repo.save(b, "att-B")
        val ctx = contextFor("att-A", sectionId = "sec-2")!!
        val text = serializeStructuralContext(ctx)
        assertEquals(a.id, ctx.outlineId)
        assertFalse(text.contains("Conteúdo A de confiança"))
        assertFalse(text.contains("Conteúdo B de confiança"))
        assertFalse(text.contains("Conteúdo B de esperança"))
        assertTrue(text.contains("Conteúdo A de oração"))
    }

    // ---------- Ausência / estado explícito (§25.18-20, §31-32) ----------

    @Test
    fun semS34NaoGeraBlocoEstrutural() {
        assertNull(contextFor("att-sem-s34", sectionHint = "qualquer"))
    }

    @Test
    fun objetivoAusenteNaoViraObjetivoFalso() = runBlocking {
        val text = S34Fixture.TEXT.replace(
            "Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n", ""
        )
        repo.save(S34Parser.parseS34(text), "att-1")
        val ctx = contextFor("att-1", sectionHint = "colocamos em prática")!!
        assertNull(ctx.objective)
        assertFalse(serializeStructuralContext(ctx).contains("Objetivo:"))
    }

    @Test
    fun sectionDesconhecidaNaoFingeFoco() {
        runBlocking { repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1") }
        val ctx = contextFor("att-1", sectionId = "sec-99")!!
        assertNull(ctx.currentSection)
        assertEquals(
            com.bettertalker.app.data.copilot.OutlineStructureContext.FocusState.UNKNOWN_SECTION,
            ctx.focusState
        )
        val text = serializeStructuralContext(ctx)
        assertTrue(text.contains("NÃO IDENTIFICADO"))
        assertFalse(text.contains("<= PONTO ATUAL"))
    }

    @Test
    fun dicaAmbiguaNaoFingeFoco() {
        runBlocking { repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1") }
        val ctx = contextFor("att-1", sectionHint = "assunto nenhum corresponde")!!
        assertNull(ctx.currentSection)
        assertEquals(
            com.bettertalker.app.data.copilot.OutlineStructureContext.FocusState.UNMATCHED_HINT,
            ctx.focusState
        )
    }

    // ---------- Ordem (§25.8-9) ----------

    @Test
    fun scoreNaoAlteraOrdemEstrutural() {
        runBlocking {
            val doc = S34Parser.parseS34(
                "S-34\n\nTema: T\n\nObjetivo:\nObjetivo detalhado o suficiente.\n\n" +
                    "1. Primeiro (2 min)\n   neutro.\n\n" +
                    "2. CONFIANÇA CONFIANÇA (2 min)\n   confiança confiança.\n\n" +
                    "3. Terceiro (2 min)\n   neutro."
            )
            repo.save(doc, "att-1")
            val ctx = contextFor("att-1", query = "confiança")!!
            assertEquals(listOf(1, 2, 3), ctx.orderedSections.map { it.order })
            assertEquals(listOf("sec-1", "sec-2", "sec-3"), ctx.orderedSections.map { it.id })
        }
    }

    // ---------- CONTENT × TRAINING (§25.15-17) ----------

    @Test
    fun trainingFicaEmBlocoSeparadoENaoViraS34() {
        runBlocking { repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1") }
        val ctx = contextFor("att-1", sectionHint = "base sólida")!!
        val pack = ContextPack(
            contentSources = listOf(
                EvidenceSource(
                    id = "c1", reference = "A Sentinela", text = "fato de conteúdo",
                    sourceType = SourceType.CONTENT, publication = "w", section = null,
                    paragraph = null, page = null
                )
            ),
            trainingSources = listOf(trainingSource)
        )
        val block = serializePack(pack, structural = ctx)
        // Treinamento em bloco próprio, com aviso de não-fato.
        assertTrue(block.contains("ORIENTAÇÕES DE ORATÓRIA"))
        assertTrue(block.contains("NÃO usar como fatos"))
        // S-34 tem bloco próprio e não é rotulado como treinamento.
        assertTrue(block.contains("--- S-34 (ESTRUTURA DO DISCURSO) ---"))
        val s34Part = block.substringAfter("--- S-34").substringBefore("--- FIM DA ESTRUTURA")
        assertFalse(s34Part.contains("ORIENTAÇÕES DE ORATÓRIA"))
        assertFalse(s34Part.contains(trainingSource.text))
        // Conteúdo também não é marcado como treinamento.
        val contentPart = block.substringAfter("FONTES DE CONTEÚDO")
            .substringBefore("FIM DAS FONTES DE CONTEÚDO")
        assertFalse(contentPart.contains(trainingSource.text))
        // S-34 vem ANTES do conteúdo (prioridade §22).
        assertTrue(block.indexOf("ESTRUTURA DO DISCURSO") < block.indexOf("FONTES DE CONTEÚDO"))
    }

    // ---------- Prompt: regras como contrato (§26) ----------

    @Test
    fun regrasDoS34EstaoNoPrompt() {
        val rules = S34_PROMPT_RULES.lowercase()
        assertTrue(rules.contains("preserve a ordem dos pontos"))
        assertTrue(rules.contains("não invente novos pontos"))
        assertTrue(rules.contains("be/th apenas para orientar como apresentar"))
        assertTrue(rules.contains("nunca use be/th como fonte factual"))
        assertTrue(rules.contains("não encontrei suporte suficiente nas fontes disponíveis."))
        assertTrue(rules.contains("não antecipe"))
        assertTrue(rules.contains("criatividade"))
    }

    @Test
    fun clausulaSoEntraQuandoHaEstrutura() {
        runBlocking { repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1") }
        val ctx = contextFor("att-1", sectionHint = "base sólida")!!
        val pack = ContextPack(emptyList(), emptyList())
        val comEstrutura = buildChatPrompt(
            message = "quantos pontos tem?", history = emptyList(), isFirstMessage = true,
            pack = pack, blockTitle = null, blockMinutes = null, blockText = "x",
            structural = ctx
        )
        val semEstrutura = buildChatPrompt(
            message = "quantos pontos tem?", history = emptyList(), isFirstMessage = true,
            pack = pack, blockTitle = null, blockMinutes = null, blockText = "x"
        )
        assertTrue(comEstrutura.contains("REGRAS DO S-34"))
        assertTrue(comEstrutura.contains("ESTRUTURA DO DISCURSO"))
        assertFalse(semEstrutura.contains("REGRAS DO S-34"))
        assertFalse(semEstrutura.contains("ESTRUTURA DO DISCURSO"))
    }

    // ---------- Teste ponta a ponta (§28) ----------

    @Test
    fun pontaAPontaFixtureAtePrompt() = runBlocking {
        // fixture → parser → persistência → retrieval → ContextPack → prompt
        val doc = S34Parser.parseS34(S34Fixture.TEXT)
        repo.save(doc, "att-1")
        val result = retriever.retrieve(
            "att-1", sectionHint = "colocamos em prática", query = "deixe mais natural"
        )
        val persisted = repo.getBySource("att-1")!!
        val structural = structuralContextOf(result, persisted)!!
        val pack = ContextPack(
            contentSources = listOf(
                EvidenceSource(
                    id = "c1", reference = "A Sentinela", text = "conteúdo complementar",
                    sourceType = SourceType.CONTENT, publication = "w", section = null,
                    paragraph = null, page = null
                )
            ),
            trainingSources = listOf(trainingSource)
        )
        val prompt = buildChatPrompt(
            message = "Quais textos bíblicos estão ligados ao ponto 2?",
            history = emptyList(), isFirstMessage = true, pack = pack,
            blockTitle = "A fé cresce quando colocamos em prática o que aprendemos",
            blockMinutes = 5, blockText = "texto do bloco",
            structural = structural
        )
        // Objetivo
        assertTrue(prompt.contains("Objetivo: Mostrar como a fé pode ser fortalecida"))
        // Os três pontos, em ordem
        val p1 = prompt.indexOf("1. A fé precisa de uma base sólida")
        val p2 = prompt.indexOf("2. A fé cresce quando colocamos em prática")
        val p3 = prompt.indexOf("3. Continue fortalecendo sua fé")
        assertTrue(p1 in 0 until p2 && p2 < p3)
        // Ponto atual marcado
        assertTrue(prompt.contains("<= PONTO ATUAL"))
        assertTrue(prompt.contains("PONTO ATUAL (2):"))
        // Subpontos
        assertTrue(prompt.contains("Subponto 1:"))
        assertTrue(prompt.contains("Subponto 2:"))
        // Referências do ponto 2, com dono
        assertTrue(prompt.contains("[BIBLE]"))
        assertTrue(prompt.contains("Tiago 2:17"))
        assertTrue(prompt.contains("[PUBLICATION]"))
        assertTrue(prompt.contains("w24.02"))
        assertTrue(prompt.contains("vinculada ao ponto 2"))
        // Não vaza referências de outros pontos.
        assertFalse(prompt.contains("João 17:17"))
        assertFalse(prompt.contains("Hebreus 10:23"))
        assertFalse(prompt.contains("w24.01"))
        // BE/TH separado e marcado como técnica.
        assertTrue(prompt.contains("ORIENTAÇÕES DE ORATÓRIA"))
        assertTrue(prompt.contains("NÃO usar como fatos"))
        // Regras do S-34 presentes.
        assertTrue(prompt.contains("REGRAS DO S-34"))
    }

    // ---------- Caminho real: LlmRequest → provider → prompt ----------

    @Test
    fun estruturaChegaAoLlmRequest() {
        runBlocking {
            repo.save(S34Parser.parseS34(S34Fixture.TEXT), "att-1")
        }
        val ctx = contextFor("att-1", sectionHint = "colocamos em prática")!!
        // O provider monta o prompt a partir do request: a estrutura precisa
        // viajar no LlmRequest, senão o bloco nunca chega ao modelo.
        val fakeHttp = com.bettertalker.app.LlmProviderTestSupport.okHttp()
        val provider = com.bettertalker.app.data.llm.GeminiProvider(
            apiKey = "k", http = fakeHttp, log = {}
        )
        runBlocking {
            provider.generate(
                com.bettertalker.app.data.llm.LlmRequest(
                    text = "corpo",
                    message = "quantos pontos?",
                    contextPack = ContextPack(emptyList(), emptyList()),
                    structural = ctx
                )
            )
        }
        val body = fakeHttp.bodies.first()
        assertTrue(body.contains("ESTRUTURA DO DISCURSO"))
        assertTrue(body.contains("REGRAS DO S-34"))
        assertTrue(body.contains("Tiago 2:17"))
        assertTrue(body.contains("<= PONTO ATUAL"))
    }

    // ---------- Legado (regressão §29) ----------

    @Test
    fun promptLegadoContinuaIgualSemS34() {
        val pack = ContextPack(
            contentSources = listOf(
                EvidenceSource(
                    id = "c1", reference = "Fonte", text = "conteúdo",
                    sourceType = SourceType.CONTENT, publication = "p", section = null,
                    paragraph = null, page = null
                )
            ),
            trainingSources = listOf(trainingSource)
        )
        val prompt = buildChatPrompt(
            message = "pergunta", history = emptyList(), isFirstMessage = true,
            pack = pack, blockTitle = "Introdução", blockMinutes = 3, blockText = "corpo"
        )
        assertTrue(prompt.contains("FONTES DE CONTEÚDO"))
        assertTrue(prompt.contains("ORIENTAÇÕES DE ORATÓRIA"))
        assertFalse(prompt.contains("S-34"))
        assertFalse(prompt.contains("REGRAS DO S-34"))
        assertTrue(prompt.contains("Mensagem do usuário: \"pergunta\""))
        assertNotNull(prompt)
    }
}
