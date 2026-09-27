package com.bettertalker.app

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.repo.S34OutlineRepository
import com.bettertalker.app.data.repo.S34StructuralRetriever
import com.bettertalker.app.data.s34.S34Parser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fase 19-B.4 — retriever estrutural sobre a persistência (B.3).
 *
 * Testes obrigatórios do enunciado:
 * - ISOLAMENTO: S34-A/sec-2 com query "confiança" não traz S34-A/sec-1,
 *   S34-B/sec-1 nem S34-B/sec-2;
 * - ORDEM: sec-1→sec-2→sec-3 sobrevive mesmo com score maior na sec-2;
 * - legado: sem outline persistido o resultado é `NoOutline` (o chamador
 *   segue no índice textual; este retriever não inventa escopo).
 */
class S34StructuralRetrieverTest {

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

    private val s34a = S34Parser.parseS34(
        "S-34 A\n\nTema: A\n\nObjetivo:\nObjetivo A detalhado com palavras suficientes.\n\n" +
            "1. CONFIANÇA (2 min)\n   Conteúdo A de confiança.\n\n" +
            "2. ORAÇÃO (2 min)\n   Conteúdo A de oração."
    )
    private val s34b = S34Parser.parseS34(
        "S-34 B\n\nTema: B\n\nObjetivo:\nObjetivo B detalhado com palavras suficientes.\n\n" +
            "1. CONFIANÇA (2 min)\n   Conteúdo B de confiança.\n\n" +
            "2. ESPERANÇA (2 min)\n   Conteúdo B de esperança."
    )

    private lateinit var dao: FakeS34Dao

    @Before
    fun setup() {
        dao = FakeS34Dao()
        repo = S34OutlineRepository(dao)
        retriever = S34StructuralRetriever(repo)
    }

    private fun sectionText(r: S34StructuralRetriever.Result): String {
        val v = (r as S34StructuralRetriever.Result.SectionFocus).view
        return v.entries.joinToString("\n") { it.text }
    }

    // ---------- ISOLAMENTO (obrigatório do enunciado) ----------

    @Test
    fun isolamentoNaoVazaOutraSecaoNemOutroS34() = runBlocking {
        repo.save(s34a, "att-A")
        repo.save(s34b, "att-B")
        val r = retriever.retrieve(
            sourceAttachmentId = "att-A",
            sectionId = "sec-2",
            query = "confiança"
        )
        assertTrue(r is S34StructuralRetriever.Result.SectionFocus)
        val text = sectionText(r)
        // Não pode conter nenhuma das três origens proibidas.
        assertFalse("vazou S34-A/sec-1", text.contains("Conteúdo A de confiança"))
        assertFalse("vazou S34-B/sec-1", text.contains("Conteúdo B de confiança"))
        assertFalse("vazou S34-B/sec-2", text.contains("Conteúdo B de esperança"))
        // E contém o ponto pedido.
        assertTrue(text.contains("Conteúdo A de oração"))
        // Outline correto.
        assertEquals(s34a.id, (r as S34StructuralRetriever.Result.SectionFocus).view.outlineId)
    }

    @Test
    fun queryNaoPromoveOutraSecao() = runBlocking {
        repo.save(s34a, "att-A")
        // "confiança" casa forte na sec-1, mas o escopo é sec-2.
        val r = retriever.retrieve("att-A", sectionId = "sec-2", query = "confiança")
        val v = (r as S34StructuralRetriever.Result.SectionFocus).view
        assertEquals("sec-2", v.sectionId)
        assertFalse(sectionText(r).contains("Conteúdo A de confiança"))
    }

    // ---------- ORDEM (obrigatório do enunciado) ----------

    @Test
    fun ordemDocumentalSobreviveComScoreMaiorEmSec2() = runBlocking {
        val doc = S34Parser.parseS34(
            "S-34\n\nTema: T\n\nObjetivo:\nObjetivo detalhado o bastante para o teste.\n\n" +
                "1. Primeiro ponto (2 min)\n   corpo neutro.\n\n" +
                "2. CONFIANÇA CONFIANÇA CONFIANÇA (2 min)\n   confiança confiança confiança.\n\n" +
                "3. Terceiro ponto (2 min)\n   corpo neutro."
        )
        repo.save(doc, "att-1")
        val r = retriever.retrieve("att-1", query = "confiança")
        assertTrue(r is S34StructuralRetriever.Result.DocumentScope)
        val sections = (r as S34StructuralRetriever.Result.DocumentScope).sections
        assertEquals(listOf(1, 2, 3), sections.map { it.documentOrder })
        assertEquals(listOf("sec-1", "sec-2", "sec-3"), sections.map { it.sectionId })
    }

    // ---------- Legado intacto ----------

    @Test
    fun semOutlinePersistidoNaoInventaEscopo() = runBlocking {
        val r = retriever.retrieve("att-sem-s34", sectionId = "sec-2")
        assertEquals(S34StructuralRetriever.Result.NoOutline, r)
    }

    @Test
    fun semDicaEscopoEDocumentoNaoOutroS34() = runBlocking {
        repo.save(s34a, "att-A")
        repo.save(s34b, "att-B")
        val r = retriever.retrieve("att-A")
        assertTrue(r is S34StructuralRetriever.Result.DocumentScope)
        val sections = (r as S34StructuralRetriever.Result.DocumentScope).sections
        assertEquals(s34a.id, r.outlineId)
        assertEquals(listOf("sec-1", "sec-2"), sections.map { it.sectionId })
    }

    // ---------- Ponto atual ----------

    @Test
    fun resolvePontoAtualPorDica() = runBlocking {
        repo.save(s34a, "att-A")
        val r = retriever.retrieve("att-A", sectionHint = "ORAÇÃO")
        assertTrue(r is S34StructuralRetriever.Result.SectionFocus)
        assertEquals("sec-2", (r as S34StructuralRetriever.Result.SectionFocus).view.sectionId)
    }

    @Test
    fun dicaAmbiguaNaoCaiNoDocumento() = runBlocking {
        repo.save(s34a, "att-A")
        val r = retriever.retrieve("att-A", sectionHint = "nenhum ponto corresponde a isto")
        assertTrue(r is S34StructuralRetriever.Result.UnmatchedSection)
    }

    @Test
    fun sectionIdInexistenteNaoCaiNoDocumento() = runBlocking {
        repo.save(s34a, "att-A")
        val r = retriever.retrieve("att-A", sectionId = "sec-99")
        assertTrue(r is S34StructuralRetriever.Result.UnknownSection)
        assertEquals("sec-99", (r as S34StructuralRetriever.Result.UnknownSection).requested)
    }

    @Test
    fun sectionIdTemPrecedenciaSobreDica() = runBlocking {
        repo.save(s34a, "att-A")
        val r = retriever.retrieve("att-A", sectionId = "sec-1", sectionHint = "ORAÇÃO")
        assertEquals("sec-1", (r as S34StructuralRetriever.Result.SectionFocus).view.sectionId)
    }

    // ---------- Provenance ----------

    @Test
    fun provenanceChegaIntactaNaView() = runBlocking {
        repo.save(s34a, "att-A")
        val r = retriever.retrieve("att-A", sectionId = "sec-2")
        val v = (r as S34StructuralRetriever.Result.SectionFocus).view
        assertTrue(v.entries.all { it.sourceLine > 0 })
        assertTrue(v.entries.all { it.ownerId.isNotBlank() })
        assertEquals("sec-2", v.sectionId)
        assertEquals(2, v.documentOrder)
    }
}
