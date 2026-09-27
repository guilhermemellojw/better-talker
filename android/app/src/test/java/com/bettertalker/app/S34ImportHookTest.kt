package com.bettertalker.app

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.repo.S34OutlineRepository
import com.bettertalker.app.data.repo.S34StructuralRetriever
import com.bettertalker.app.data.s34.S34ImportHook
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.copilot.S34_PROMPT_RULES
import com.bettertalker.app.data.copilot.buildChatPrompt
import com.bettertalker.app.data.copilot.structuralContextOf
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fase 19-B.6 — hook de importação + aceitação A–H (Android).
 *
 * O hook é exercitado como o worker o chama: texto extraído + id do
 * attachment → detector → parser → persistência. Espelho conceitual de
 * src/copilot/__tests__/s34ImportAcceptance.test.ts.
 */
class S34ImportHookTest {

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

    private lateinit var dao: FakeS34Dao
    private lateinit var repo: S34OutlineRepository
    private lateinit var retriever: S34StructuralRetriever
    private val silent: (String, String) -> Unit = { _, _ -> }

    @Before
    fun setup() {
        dao = FakeS34Dao()
        repo = S34OutlineRepository(dao)
        retriever = S34StructuralRetriever(repo)
    }

    private fun import(attachmentId: String, text: String) = runBlocking {
        S34ImportHook.onExtracted(dao, attachmentId, text, silent)
    }

    // ---------- Fluxo real do hook ----------

    @Test
    fun importarS34PersisteAutomaticamente() {
        val out = import("att-1", S34Fixture.TEXT)
        assertTrue(out is S34ImportHook.Outcome.Saved)
        val saved = out as S34ImportHook.Outcome.Saved
        assertEquals(3, saved.sections)
        // 3 versículos + 2 publicações da fixture.
        assertEquals(5, saved.references)
        runBlocking {
            val doc = repo.getBySource("att-1")
            assertNotNull(doc)
            assertEquals("Como fortalecer a fé", doc!!.title)
            assertEquals(listOf(1, 2, 3), doc.sections.map { it.order })
            assertEquals(2, doc.sections[1].subsections.size)
        }
    }

    @Test
    fun documentoComumSegueFluxoLegado() {
        val comum = ("Ata da reunião de condomínio.\nPresentes: síndico e moradores.\n" +
            "Pauta: pintura da fachada.\n").repeat(8)
        assertEquals(S34ImportHook.Outcome.NotS34, import("att-comum", comum))
        assertNull(runBlocking { repo.getBySource("att-comum") })
    }

    @Test
    fun associacaoEporAttachmentIdNaoPorTitulo() {
        val a = S34Fixture.TEXT
        val b = S34Fixture.TEXT.replace("Como fortalecer a fé", "Como fortalecer a esperança")
        import("att-A", a)
        import("att-B", b)
        runBlocking {
            assertEquals("Como fortalecer a fé", repo.getBySource("att-A")!!.title)
            assertEquals("Como fortalecer a esperança", repo.getBySource("att-B")!!.title)
            assertFalse(repo.getBySource("att-A")!!.id == repo.getBySource("att-B")!!.id)
        }
    }

    @Test
    fun idempotenciaNaoDuplica() {
        import("att-1", S34Fixture.TEXT)
        import("att-1", S34Fixture.TEXT)
        assertEquals(1, dao.outlines.size)
        assertEquals(3, dao.sections.size)
        assertEquals(5, dao.refs.size)
    }

    @Test
    fun atualizacaoSubstituiSemResiduos() {
        import("att-1", S34Fixture.TEXT)
        val v2 = S34Fixture.TEXT.replace(
            "3. Continue fortalecendo sua fé (3 min)",
            "3. Continue fortalecendo sua fé (3 min)\n\n4. Persevere até o fim (2 min)\n   Leia Judas 25."
        )
        import("att-1", v2)
        assertEquals(1, dao.outlines.size)
        assertEquals(4, dao.sections.size)
        val doc = runBlocking { repo.getBySource("att-1")!! }
        assertEquals(4, doc.sections.size)
        assertTrue(dao.refs.values.all { it.outlineId == doc.id })
    }

    @Test
    fun falhaDeParseNaoPersisteEstruturaFalsa() {
        // Marcador + objetivo + publicações (2 sinais) mas SEM pontos numerados.
        val semPontos = "S-34 — rascunho (texto sintético de teste)\n" +
            "Tema: Um tema\n\n" +
            "Objetivo:\nRefletir sobre algo com calma e ordem durante a reunião.\n\n" +
            "Consulte a publicação de estudo w24.01, §3.\n".repeat(4)
        val out = import("att-x", semPontos)
        assertTrue(out is S34ImportHook.Outcome.ParseFailed)
        assertEquals(0, dao.outlines.size)
    }

    @Test
    fun documentoAmbiguoNaoViraS34() {
        val ambiguo = "S-34\n" +
            "Leia a Bíblia com atenção todos os dias e ore sempre. ".repeat(8) +
            "\nLeia João 17:17. Leia Tiago 2:17."
        assertEquals(S34ImportHook.Outcome.NotS34, import("att-amb", ambiguo))
        assertNull(runBlocking { repo.getBySource("att-amb") })
    }

    // ---------- Isolamento entre dois S-34 importados ----------

    @Test
    fun isolamentoEntreDoisS34Importados() {
        import(
            "att-A",
            "S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A com detalhes suficientes para o teste.\n\n" +
                "1. CONFIANÇA (2 min)\n   Conteúdo A de confiança, com explicação prática.\n" +
                "   Consulte a publicação de estudo w24.01, §3.\n\n" +
                "2. ORAÇÃO (2 min)\n   Conteúdo A de oração, com explicação prática.\n" +
                "   Consulte a publicação de estudo w24.02, §5."
        )
        import(
            "att-B",
            "S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B com detalhes suficientes para o teste.\n\n" +
                "1. CONFIANÇA (2 min)\n   Conteúdo B de confiança, com explicação prática.\n" +
                "   Consulte a publicação de estudo w24.04, §1.\n\n" +
                "2. ESPERANÇA (2 min)\n   Conteúdo B de esperança, com explicação prática.\n" +
                "   Consulte a publicação de estudo w24.05, §2."
        )
        runBlocking {
            val r = retriever.retrieve("att-A", sectionId = "sec-2", query = "confiança")
            assertTrue(r is S34StructuralRetriever.Result.SectionFocus)
            val text = (r as S34StructuralRetriever.Result.SectionFocus).view.entries
                .joinToString("\n") { it.text }
            assertFalse(text.contains("Conteúdo A de confiança"))
            assertFalse(text.contains("Conteúdo B de confiança"))
            assertFalse(text.contains("Conteúdo B de esperança"))
            assertTrue(text.contains("Conteúdo A de oração"))
            val rb = retriever.retrieve("att-B", sectionId = "sec-2")
            assertTrue(
                (rb as S34StructuralRetriever.Result.SectionFocus).view.entries
                    .joinToString("\n") { it.text }.contains("Conteúdo B de esperança")
            )
        }
    }

    // ---------- Remoção ----------

    @Test
    fun removerSourceRemoveOutline() {
        import("att-1", S34Fixture.TEXT)
        assertNotNull(runBlocking { repo.getBySource("att-1") })
        runBlocking { repo.deleteBySource("att-1") }
        assertNull(runBlocking { repo.getBySource("att-1") })
        assertEquals(0, dao.sections.size)
        assertEquals(0, dao.subs.size)
        assertEquals(0, dao.refs.size)
    }

    // ---------- Reload ----------

    @Test
    fun reloadMantemOutlineERetrieval() {
        import("att-1", S34Fixture.TEXT)
        // "reload": nova instância sobre o mesmo store.
        val repo2 = S34OutlineRepository(dao)
        val retriever2 = S34StructuralRetriever(repo2)
        runBlocking {
            val doc = repo2.getBySource("att-1")
            assertNotNull(doc)
            assertEquals(3, doc!!.sections.size)
            val r = retriever2.retrieve("att-1", sectionHint = "colocamos em prática")
            assertTrue(r is S34StructuralRetriever.Result.SectionFocus)
        }
    }

    // ---------- Aceitação A–H ----------

    private fun ctxFor() = runBlocking {
        import("att-1", S34Fixture.TEXT)
        val doc = repo.getBySource("att-1")!!
        val r = retriever.retrieve("att-1", sectionHint = "colocamos em prática")
        structuralContextOf(r, doc)!!
    }

    @Test
    fun aceitacaoA_objetivo() {
        val ctx = ctxFor()
        assertEquals(
            "Mostrar como a fé pode ser fortalecida por meio da Palavra de Deus.",
            ctx.objective
        )
    }

    @Test
    fun aceitacaoB_pontosPrincipais() {
        val ctx = ctxFor()
        assertEquals(
            listOf(
                "1. A fé precisa de uma base sólida",
                "2. A fé cresce quando colocamos em prática o que aprendemos",
                "3. Continue fortalecendo sua fé"
            ),
            ctx.orderedSections.map { "${it.order}. ${it.title}" }
        )
    }

    @Test
    fun aceitacaoC_sequenciaNaoDependeDeScore() {
        val ctx = ctxFor()
        assertEquals(listOf(1, 2, 3), ctx.orderedSections.map { it.order })
        assertEquals(listOf("sec-1", "sec-2", "sec-3"), ctx.orderedSections.map { it.id })
        assertEquals("sec-2", ctx.orderedSections.first { it.isCurrent }.id)
    }

    @Test
    fun aceitacaoD_bibliasDoPonto2() {
        val ctx = ctxFor()
        val bible = ctx.currentSection!!.references.filter { it.type == S34RefType.BIBLE }
        assertTrue(bible.any { it.rawText.contains("Tiago 2:17") })
        assertFalse(bible.any { it.rawText.contains("João 17:17") })
        assertFalse(bible.any { it.rawText.contains("Hebreus 10:23") })
    }

    @Test
    fun aceitacaoE_publicacoesDoPonto3() {
        val text = S34Fixture.TEXT.replace(
            "3. Continue fortalecendo sua fé (3 min)\n   Leia Hebreus 10:23.",
            "3. Continue fortalecendo sua fé (3 min)\n   Leia Hebreus 10:23.\n" +
                "   Consulte a publicação de estudo w24.03, §7."
        )
        runBlocking { import("att-1", text) }
        val ctx = runBlocking {
            val doc = repo.getBySource("att-1")!!
            structuralContextOf(retriever.retrieve("att-1", sectionId = "sec-3"), doc)!!
        }
        val pubs = ctx.currentSection!!.references.filter { it.type == S34RefType.PUBLICATION }
        assertTrue(pubs.any { it.rawText.contains("w24.03") })
        assertTrue(pubs.all { it.ownerId == "sec-3" || it.ownerId.startsWith("sec-3-") })
        assertFalse(pubs.any { it.rawText.contains("w24.02") })
    }

    @Test
    fun aceitacaoF_desenvolvimentoDoPonto2Completo() {
        val ctx = ctxFor()
        assertNotNull(ctx.objective)
        assertEquals(2, ctx.currentSection!!.order)
        assertTrue(ctx.currentSection!!.content.contains("Tiago 2:17"))
        assertEquals(2, ctx.currentSection!!.subsections.size)
        assertTrue(ctx.currentSection!!.references.isNotEmpty())
        assertEquals(listOf(1, 2, 3), ctx.orderedSections.map { it.order })
    }

    @Test
    fun aceitacaoG_introducaoTemObjetivoPrimeiroPontoEBEthSeparado() {
        val ctx = ctxFor()
        assertNotNull(ctx.objective)
        assertEquals(1, ctx.orderedSections[0].order)
        val prompt = buildChatPrompt(
            message = "Como posso introduzir?",
            history = emptyList(), isFirstMessage = true,
            pack = ContextPack(
                emptyList(),
                listOf(
                    EvidenceSource(
                        id = "t1", reference = "Beneficie-se lição 1",
                        text = "abertura com pergunta", sourceType = SourceType.TRAINING,
                        publication = "be", section = null, paragraph = null, page = null,
                        trainingCategory = TrainingCategory.INTRODUCTION
                    )
                )
            ),
            blockTitle = null, blockMinutes = null, blockText = "corpo",
            structural = ctx
        )
        assertTrue(prompt.contains("ESTRUTURA DO DISCURSO"))
        assertTrue(prompt.contains("1. A fé precisa de uma base sólida"))
        assertTrue(prompt.contains("ORIENTAÇÕES DE ORATÓRIA"))
        assertTrue(prompt.contains("NÃO usar como fatos"))
        assertFalse(prompt.contains("\"introduction\""))
    }

    @Test
    fun aceitacaoH_conclusaoTemObjetivoUltimoPontoEBEthSeparado() {
        val ctx = ctxFor()
        val last = ctx.orderedSections.last()
        assertEquals(3, last.order)
        assertNotNull(ctx.objective)
        // Sem objeto "conclusion" no modelo persistido.
        val fields = com.bettertalker.app.data.s34.S34Document::class.java.declaredFields
            .filter { !it.isSynthetic }.map { it.name }
        assertFalse(fields.any { it.contains("conclus", ignoreCase = true) })
    }

    // ---------- ContextPack real ----------

    @Test
    fun contextPackRealTemTudoQueOPromptPrecisa() {
        val ctx = ctxFor()
        val prompt = buildChatPrompt(
            message = "Quais textos bíblicos estão ligados ao ponto 2?",
            history = emptyList(), isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "A fé cresce quando colocamos em prática o que aprendemos",
            blockMinutes = 5, blockText = "corpo",
            structural = ctx
        )
        assertTrue(prompt.contains("Objetivo: Mostrar como a fé pode ser fortalecida"))
        assertTrue(prompt.contains("<= PONTO ATUAL"))
        assertTrue(prompt.contains("PONTO ATUAL (2):"))
        assertTrue(prompt.contains("Subponto 1:"))
        assertTrue(prompt.contains("[BIBLE]"))
        assertTrue(prompt.contains("Tiago 2:17"))
        assertTrue(prompt.contains("[PUBLICATION]"))
        assertTrue(prompt.contains("w24.02"))
        assertTrue(prompt.contains("REGRAS DO S-34"))
        assertFalse(prompt.contains("João 17:17"))
        assertFalse(prompt.contains("Hebreus 10:23"))
    }

    // ---------- S-34 sem objetivo ----------

    @Test
    fun s34SemObjetivoImportaComObjectiveNull() {
        val text = S34Fixture.TEXT.replace(
            "Objetivo:\nMostrar como a fé pode ser fortalecida por meio da Palavra de Deus.\n\n", ""
        )
        import("att-1", text)
        val doc = runBlocking { repo.getBySource("att-1")!! }
        assertNull(doc.objective)
        val ctx = runBlocking {
            structuralContextOf(retriever.retrieve("att-1", sectionHint = "colocamos em prática"), doc)!!
        }
        assertNull(ctx.objective)
        assertFalse(
            com.bettertalker.app.data.copilot.serializeStructuralContext(ctx).contains("Objetivo:")
        )
    }
}
