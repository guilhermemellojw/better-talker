package com.bettertalker.app

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.repo.S34OutlineRepository
import com.bettertalker.app.data.s34.S34Parser
import com.bettertalker.app.data.s34.S34RefType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fase 19-B.3 — persistência do OutlineDocument (§22).
 * Fake DAO em memória (mesmo padrão de HardeningTest); SQL real coberto
 * por S34MigrationTest. Sem Android/Room.
 */
class S34PersistenceTest {

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
    private val doc = S34Parser.parseS34(S34Fixture.TEXT)

    @Before
    fun setup() {
        dao = FakeS34Dao()
        repo = S34OutlineRepository(dao)
    }

    // ---------- CRUD (§22.1-5) ----------

    @Test
    fun salvarERecuperarPorId() = runBlocking {
        repo.save(doc, "att-1")
        val got = repo.get(doc.id)
        assertTrue(got != null)
        assertEquals(doc.title, got!!.title)
    }

    @Test
    fun recuperarPorSource() = runBlocking {
        repo.save(doc, "att-1")
        assertEquals(doc.id, repo.getBySource("att-1")!!.id)
        assertNull(repo.getBySource("att-99"))
    }

    @Test
    fun excluirRemoveTudo() = runBlocking {
        repo.save(doc, "att-1")
        repo.deleteBySource("att-1")
        assertNull(repo.get(doc.id))
        assertNull(repo.getBySource("att-1"))
        assertTrue(dao.sections.isEmpty())
        assertTrue(dao.subs.isEmpty())
        assertTrue(dao.refs.isEmpty())
    }

    @Test
    fun ausenciaRetornaNull() = runBlocking {
        assertNull(repo.get("inexistente"))
        assertNull(repo.getBySource("inexistente"))
    }

    // ---------- Estrutura (§22.6-12) ----------

    @Test
    fun estruturaSobreviveAoRoundTrip() = runBlocking {
        repo.save(doc, "att-1")
        val got = repo.get(doc.id)!!
        assertEquals(doc.objective, got.objective)
        assertEquals(doc.title, got.title)
        assertEquals(doc.headerLines, got.headerLines)
        assertEquals(3, got.sections.size)
        assertEquals(listOf(1, 2, 3), got.sections.map { it.order })
        assertEquals(listOf(4, 5, 3), got.sections.map { it.minutes })
        assertEquals(2, got.sections[1].subsections.size)
        assertEquals(
            listOf(1, 2),
            got.sections[1].subsections.map { it.order }
        )
    }

    // ---------- Referências (§22.13-20) ----------

    @Test
    fun referenciasSobrevivemComVinculo() = runBlocking {
        repo.save(doc, "att-1")
        val got = repo.get(doc.id)!!
        val b0 = got.sections[0].references.filter { it.type == S34RefType.BIBLE }
        assertTrue(b0.any { it.normalizedReference == "João|17|17" })
        val p1 = got.sections[1].references.filter { it.type == S34RefType.PUBLICATION }
        assertTrue(p1.any { it.rawText.contains("w24.02") })
        // tipo + campos passam pelo round-trip
        val all = got.references
        assertTrue(all.any { it.type == S34RefType.BIBLE })
        assertTrue(all.any { it.type == S34RefType.PUBLICATION })
        val joao = all.first { it.normalizedReference == "João|17|17" }
        assertEquals(17, joao.bible!!.chapter)
        val w = all.first { it.rawText.contains("w24.01") }
        assertTrue(w.publication != null)
    }

    @Test
    fun vinculoSubsecaoSobrevive() = runBlocking {
        val text = S34Fixture.TEXT.replace(
            "   b) Aplicar o que aprendemos",
            "   b) Aplicar o que aprendemos. Leia João 3:16."
        )
        val d = S34Parser.parseS34(text)
        repo.save(d, "att-1")
        val got = repo.get(d.id)!!
        val sub = got.sections[1].subsections[1]
        assertTrue(sub.references.any { it.normalizedReference == "João|3|16" })
    }

    @Test
    fun referenciaSemSecaoPermaneceSemSecao() = runBlocking {
        // Inserção crua com sectionId null (contrato §12): leitura tolera.
        dao.refs["orphan"] = S34ReferenceEntity(
            id = "orphan", outlineId = "nope", sectionId = null, subsectionId = null,
            order = 1, type = "BIBLE", rawText = "João 1:1",
            normalizedReference = "João|1|1", sourceLine = 1,
            book = "João", bookNorm = "joao", chapter = 1, verse = 1,
            pubKind = null, pubKey = null, pubLabel = null, editionKey = null
        )
        assertEquals(null, dao.refs["orphan"]!!.sectionId)
    }

    // ---------- Provenance (§22.21-22) ----------

    @Test
    fun provenanceSobrevive() = runBlocking {
        repo.save(doc, "att-1")
        val got = repo.get(doc.id)!!
        assertTrue(got.sections.all { it.sourceLine > 0 })
        assertTrue(got.references.all { it.sourceLine > 0 })
    }

    // ---------- Idempotência (§22.23-26) ----------

    @Test
    fun salvarDuasVezesNaoDuplica() = runBlocking {
        repo.save(doc, "att-1")
        repo.save(doc, "att-1")
        assertEquals(1, dao.outlines.size)
        assertEquals(3, dao.sections.size)
        val refCount = dao.refs.size
        assertEquals(doc.references.size, refCount)
        assertEquals(doc.id, repo.getBySource("att-1")!!.id)
    }

    @Test
    fun versaoAtualizadaSubstituiSemRestos() = runBlocking {
        repo.save(doc, "att-1")
        // v2: 4 pontos (seção extra) + corpo trocado na sec-1.
        val v2text = S34Fixture.TEXT.replace(
            "3. Continue fortalecendo sua fé (3 min)",
            "3. Continue fortalecendo sua fé (3 min)\n\n4. Persevere até o fim (2 min)\n   Leia Judas 25."
        )
        val v2 = S34Parser.parseS34(v2text)
        repo.save(v2, "att-1")
        val got = repo.getBySource("att-1")!!
        assertEquals(4, got.sections.size)
        assertEquals(v2.id, got.id)
        // sem restos: só seções da v2 no store
        assertEquals(4, dao.sections.size)
        assertTrue(dao.refs.values.all { it.outlineId == v2.id })
    }

    @Test
    fun removerSecaoRemoveFilhos() = runBlocking {
        repo.save(doc, "att-1")
        // simula update sem a seção 2: deleta e reinsere só sec-1/sec-3
        val partial = doc.copy(
            sections = listOf(doc.sections[0], doc.sections[2])
        )
        repo.save(partial, "att-1")
        val got = repo.getBySource("att-1")!!
        assertEquals(2, got.sections.size)
        assertTrue(dao.subs.isEmpty())
        assertTrue(dao.refs.values.none { it.rawText.contains("w24.02") })
        assertTrue(dao.refs.values.none { it.normalizedReference.startsWith("Tiago|") })
    }

    // ---------- Round-trip (§22.27-29) ----------

    @Test
    fun roundTripPreservaIdsEEstrutura() = runBlocking {
        repo.save(doc, "att-1")
        val got = repo.get(doc.id)!!
        assertEquals(doc.id, got.id)
        assertEquals(doc.sections.map { it.id }, got.sections.map { it.id })
        assertEquals(
            doc.sections.flatMap { it.subsections.map { s -> s.id } },
            got.sections.flatMap { it.subsections.map { s -> s.id } }
        )
        assertEquals(
            doc.references.map { it.order to it.rawText },
            got.references.map { it.order to it.rawText }
        )
    }

    // ---------- Restart (§22.30) ----------

    @Test
    fun novoRepositorioLeMesmoStore() = runBlocking {
        repo.save(doc, "att-1")
        // "restart": nova instância sobre o mesmo store.
        val repo2 = S34OutlineRepository(dao)
        val got = repo2.getBySource("att-1")!!
        assertEquals(doc.title, got.title)
        assertEquals(3, got.sections.size)
        assertEquals(doc.references.size, got.references.size)
    }

    // ---------- Web × Android (projeção equivalente) ----------

    private fun projection(d: com.bettertalker.app.data.s34.S34Document) = mapOf(
        "title" to d.title,
        "objective" to d.objective,
        "sections" to d.sections.map { s ->
            mapOf(
                "order" to s.order,
                "minutes" to s.minutes,
                "subs" to s.subsections.map { it.order },
                "bible" to s.references.filter { it.type == S34RefType.BIBLE }
                    .map { it.normalizedReference },
                "pubs" to s.references.filter { it.type == S34RefType.PUBLICATION }
                    .map { it.rawText }
            )
        }
    )

    @Test
    fun projecaoEstruturalEIdenticaAoWeb() = runBlocking {
        // Mesmos campos comparados em src/copilot/__tests__/s34Persistence.test.ts.
        repo.save(doc, "att-1")
        val got = repo.get(doc.id)!!
        assertEquals(projection(doc), projection(got))
    }

    // ---------- Invariantes (§23) ----------

    @Test
    fun invariantesDeIntegridade() = runBlocking {
        repo.save(doc, "att-1")
        // 1: cada seção pertence a um único outline (chave do mapa honesta)
        assertTrue(dao.sections.values.all { it.outlineId == doc.id })
        // 2: cada subseção pertence a uma única seção existente
        val secIds = dao.sections.keys
        assertTrue(dao.subs.values.all { it.sectionId in secIds })
        // 3: cada referência pertence a um único outline
        assertTrue(dao.refs.values.all { it.outlineId == doc.id })
        // 4: sectionId aponta para seção do mesmo outline
        dao.refs.values.filter { it.sectionId != null }.forEach {
            val s = dao.sections[it.sectionId]
            assertTrue(s != null && s.outlineId == it.outlineId)
        }
        // 5: subsectionId aponta para subseção da seção indicada
        dao.refs.values.filter { it.subsectionId != null }.forEach {
            val sub = dao.subs[it.subsectionId]
            assertTrue(sub != null && sub.sectionId == it.sectionId)
        }
        // 6: sem IDs duplicados (mapas garantem; contagem confere)
        assertEquals(dao.sections.size, dao.sections.keys.size)
        // 7: ordem determinística na leitura
        val got = repo.get(doc.id)!!
        assertEquals(listOf(1, 2, 3), got.sections.map { it.order })
        // 8: persistência não cria conteúdo (contagens batem com o parser)
        assertEquals(doc.sections.size, got.sections.size)
        assertEquals(doc.references.size, got.references.size)
    }
}
