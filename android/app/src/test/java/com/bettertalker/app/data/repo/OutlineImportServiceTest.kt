package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointDao
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TransactionRunner
import com.bettertalker.app.domain.speech.BodyConversion
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.OutlineConversion
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SectionValidationError
import com.bettertalker.app.domain.speech.SectionValidationResult
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// ---------- Fakes (padrão do projeto: in-memory + snapshot/restore p/ teste) ----------

private class FakeSectionDao(var rows: List<SpeechSectionEntity> = emptyList()) : SpeechSectionDao {
    override suspend fun forNote(noteId: String): List<SpeechSectionEntity> =
        rows.filter { it.noteId == noteId }.sortedBy { it.order }
    override suspend fun get(id: String): SpeechSectionEntity? = rows.firstOrNull { it.id == id }
    override suspend fun upsert(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id } + section
    }
    override suspend fun upsertAll(sections: List<SpeechSectionEntity>) { sections.forEach { upsert(it) } }
    override suspend fun delete(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id }
    }
    override suspend fun deleteForNote(noteId: String) { rows = rows.filter { it.noteId != noteId } }
    // 3.2.5a: snapshot única (fakes destes testes não observam).
    override fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>> =
        flowOf(rows.filter { it.noteId == noteId }.sortedBy { it.order })
    fun snapshot(): List<SpeechSectionEntity> = rows.toList()
    fun restore(s: List<SpeechSectionEntity>) { rows = s }
}

private class FakeSubPointDao(var rows: List<SubPointEntity> = emptyList()) : SubPointDao {
    var failOnUpsertAll = false
    override suspend fun forSection(sectionId: String): List<SubPointEntity> =
        rows.filter { it.sectionId == sectionId }.sortedBy { it.order }
    override suspend fun get(id: String): SubPointEntity? = rows.firstOrNull { it.id == id }
    override suspend fun forSections(sectionIds: List<String>): List<SubPointEntity> =
        rows.filter { it.sectionId in sectionIds }.sortedWith(compareBy({ it.sectionId }, { it.order }))
    override suspend fun upsert(subPoint: SubPointEntity) {
        rows = rows.filter { it.id != subPoint.id } + subPoint
    }
    override suspend fun upsertAll(subPoints: List<SubPointEntity>) {
        if (failOnUpsertAll) throw IllegalStateException("falha simulada no upsertAll")
        subPoints.forEach { upsert(it) }
    }
    override suspend fun delete(subPoint: SubPointEntity) {
        rows = rows.filter { it.id != subPoint.id }
    }
    override suspend fun deleteForSection(sectionId: String) {
        rows = rows.filter { it.sectionId != sectionId }
    }
    // 3.2.5a: snapshot única (fakes destes testes não observam).
    override fun observeForSections(sectionIds: List<String>): Flow<List<SubPointEntity>> =
        flowOf(rows.filter { it.sectionId in sectionIds }
            .sortedWith(compareBy({ it.sectionId }, { it.order })))
    fun snapshot(): List<SubPointEntity> = rows.toList()
    fun restore(s: List<SubPointEntity>) { rows = s }
}

/** Simula o rollback do Room: snapshot antes, restore em qualquer Throwable. */
private class FakeTransactionRunner(
    private val sectionDao: FakeSectionDao,
    private val subPointDao: FakeSubPointDao,
) : TransactionRunner {
    var ran = false
    override suspend fun <R> run(block: suspend () -> R): R {
        ran = true
        val sectionsSnapshot = sectionDao.snapshot()
        val subPointsSnapshot = subPointDao.snapshot()
        return try {
            block()
        } catch (e: Throwable) {
            sectionDao.restore(sectionsSnapshot)
            subPointDao.restore(subPointsSnapshot)
            throw e
        }
    }
}

// ---------- Fixtures ----------

private fun section(
    id: String,
    noteId: String,
    order: Int,
    role: SectionRole,
    minutes: Int,
) = SpeechSection(
    id = id, noteId = noteId, order = order, role = role, title = "Seção $id",
    minutes = minutes, contentHtml = "", bibleRefs = emptyList(),
    publicationRefs = emptyList(), methodPrinciple = null,
    createdAt = 1L, updatedAt = 2L,
)

private fun subPoint(id: String, sectionId: String, order: Int) = SubPoint(
    id = id, sectionId = sectionId, order = order, outlineText = "Ponto $id",
    bibleRefs = emptyList(), publicationRefs = emptyList(), instruction = null,
    developedHtml = "", createdAt = 1L, updatedAt = 2L,
)

private fun conversion(
    noteId: String = "n1",
    prefix: String = "a",
    bodies: Int = 2,
    subPerBody: Int = 3,
    bodyMinutes: Int = 5,
): OutlineConversion {
    val intro = section("$prefix-i", noteId, 0, SectionRole.INTRO, minutes = 1)
    val bodyList = (0 until bodies).map { b ->
        val sec = section("$prefix-b$b", noteId, 1 + b, SectionRole.BODY, bodyMinutes)
        BodyConversion(sec, (0 until subPerBody).map { s -> subPoint("$prefix-sp-$b-$s", sec.id, s) })
    }
    val conclusion = section("$prefix-c", noteId, bodies + 1, SectionRole.CONCLUSION, minutes = 1)
    return OutlineConversion("Título", 10, DiscourseType.S34_DISCOURSE, intro, bodyList, conclusion)
}

class OutlineImportServiceTest {

    private fun setup(
        sectionDao: FakeSectionDao = FakeSectionDao(),
        subPointDao: FakeSubPointDao = FakeSubPointDao(),
    ): Triple<OutlineImportService, FakeSectionDao, FakeSubPointDao> {
        val runner = FakeTransactionRunner(sectionDao, subPointDao)
        return Triple(OutlineImportService(runner, sectionDao, subPointDao), sectionDao, subPointDao)
    }

    @Test
    fun persist_emptyBodies_returnsInvalid_noBody() = runBlocking {
        // 3.2.5f-pre: validação informativa — persiste mesmo assim.
        val (service, sections, subPoints) = setup()
        val result = service.persist(conversion(bodies = 0))
        assertTrue(result is SectionValidationResult.Invalid)
        assertTrue((result as SectionValidationResult.Invalid).errors.contains(SectionValidationError.NO_BODY))
        // intro + conclusion persistidos (autonomia: dado não é descartado)
        assertEquals(2, sections.rows.size)
        assertTrue(subPoints.rows.isEmpty())
    }

    @Test
    fun persist_validConversion_persistsAllSectionsAndSubPoints() = runBlocking {
        val (service, sections, subPoints) = setup()
        service.persist(conversion(bodies = 2, subPerBody = 3))
        assertEquals(4, sections.rows.size) // intro + 2 bodies + conclusion
        assertEquals(6, subPoints.rows.size)
        assertEquals(SectionRole.INTRO.name, sections.rows.first { it.id == "a-i" }.role)
        assertEquals(SectionRole.CONCLUSION.name, sections.rows.first { it.id == "a-c" }.role)
    }

    @Test
    fun persist_deletesPreviousSectionsForNote() = runBlocking {
        val (service, sections, subPoints) = setup()
        service.persist(conversion(prefix = "a"))
        service.persist(conversion(prefix = "b"))
        assertEquals(4, sections.rows.size)
        assertTrue(sections.rows.none { it.id.startsWith("a-") })
        assertEquals(6, subPoints.rows.size)
        assertTrue(subPoints.rows.none { it.id.startsWith("a-") })
    }

    @Test
    fun persist_nonPositiveMinutes_returnsInvalid() = runBlocking {
        // 3.2.5f-pre: validação informativa — persiste mesmo assim.
        val (service, sections, subPoints) = setup()
        val result = service.persist(conversion(bodyMinutes = 0))
        assertTrue(result is SectionValidationResult.Invalid)
        assertTrue((result as SectionValidationResult.Invalid).errors.contains(SectionValidationError.NON_POSITIVE_MINUTES))
        assertEquals(4, sections.rows.size)
        assertEquals(6, subPoints.rows.size)
    }

    @Test
    fun persist_subPointsArePersistedInOrder() = runBlocking {
        val (service, _, subPoints) = setup()
        service.persist(conversion(bodies = 2, subPerBody = 3))
        assertEquals(listOf(0, 1, 2), subPoints.forSection("a-b0").map { it.order })
        assertEquals(listOf(0, 1, 2), subPoints.forSection("a-b1").map { it.order })
    }

    // ---------- 3.2.4b: persistência por tipo ----------

    @Test
    fun persist_s34WithIntroAndConclusion_persistsAll() = runBlocking {
        val (service, sections, subPoints) = setup()
        service.persist(conversion(bodies = 2, subPerBody = 3))
        assertEquals(4, sections.rows.size)
        assertEquals(6, subPoints.rows.size)
    }

    @Test
    fun persist_treasures_singleBodyOnly() = runBlocking {
        val (service, sections, subPoints) = setup()
        val conv = com.bettertalker.app.domain.speech.OutlineConverter().convert(
            com.bettertalker.app.data.util.ParsedOutline(
                title = "Tesouros", totalMinutes = 10,
                sections = listOf(
                    com.bettertalker.app.data.util.OutlineSection("Ponto", 10, 0, "Ideia um.\nIdeia dois."),
                ),
            ),
            "n1", com.bettertalker.app.domain.speech.DiscourseType.TREASURES_TALK,
        )
        service.persist(conv)
        assertEquals(1, sections.rows.size)
        assertEquals("BODY", sections.rows.single().role)
        assertEquals(2, subPoints.rows.size)
    }

    @Test
    fun persist_avulso_singleBodyNoSubPoints() = runBlocking {
        val (service, sections, subPoints) = setup()
        val conv = com.bettertalker.app.domain.speech.OutlineConverter().convert(
            com.bettertalker.app.data.util.ParsedOutline(
                title = "Avulso", totalMinutes = 5,
                sections = listOf(
                    com.bettertalker.app.data.util.OutlineSection("Leitura", 5, 0, ""),
                ),
            ),
            "n1", com.bettertalker.app.domain.speech.DiscourseType.AVULSO,
        )
        service.persist(conv)
        assertEquals(1, sections.rows.size)
        assertTrue(subPoints.rows.isEmpty())
    }

    @Test
    fun persist_treasures_withIntroNullButConclusionPresent_returnsInvalid() = runBlocking {
        val (service, sections, _) = setup()
        // Construção manual inválida: conclusion presente num tipo curto.
        val base = com.bettertalker.app.domain.speech.OutlineConverter().convert(
            com.bettertalker.app.data.util.ParsedOutline(
                title = "T", totalMinutes = 10,
                sections = listOf(
                    com.bettertalker.app.data.util.OutlineSection("Ponto", 10, 0, "Ideia."),
                ),
            ),
            "n1", com.bettertalker.app.domain.speech.DiscourseType.TREASURES_TALK,
        )
        val broken = base.copy(
            conclusion = com.bettertalker.app.domain.speech.SpeechSection(
                id = "c", noteId = "n1", order = 1, role = com.bettertalker.app.domain.speech.SectionRole.CONCLUSION,
                title = "Conclusão", minutes = 1, contentHtml = "",
                bibleRefs = emptyList(), publicationRefs = emptyList(),
                methodPrinciple = null, createdAt = 1L, updatedAt = 2L,
            ),
        )
        // 3.2.5f-pre: validação informativa — persiste mesmo assim.
        val result = service.persist(broken)
        assertTrue(result is SectionValidationResult.Invalid)
        assertTrue((result as SectionValidationResult.Invalid).errors.contains(SectionValidationError.UNEXPECTED_CONCLUSION))
        assertEquals(2, sections.rows.size)
    }

    // ---------- 3.2.5f-pre: validação não-bloqueante ----------

    @Test
    fun persist_invalidStructure_doesNotThrow_returnsInvalid() = runBlocking {
        // 2 BODYs num MINISTRY_PART: inválido, mas persiste (autonomia).
        val (service, sections, _) = setup()
        val bodies = (0 until 2).map { b ->
            val sec = section("m-b$b", "n1", b, SectionRole.BODY, 5)
            BodyConversion(sec, listOf(subPoint("m-sp-$b", sec.id, 0)))
        }
        val conversion = OutlineConversion(
            "Título", 10, DiscourseType.MINISTRY_PART,
            intro = null, bodies = bodies, conclusion = null,
        )
        val result = service.persist(conversion)
        assertTrue(result is SectionValidationResult.Invalid)
        assertTrue((result as SectionValidationResult.Invalid).errors.contains(SectionValidationError.WRONG_BODY_COUNT))
        assertEquals(2, sections.rows.size)
    }

    @Test
    fun persist_validStructure_returnsValid() = runBlocking {
        val (service, _, _) = setup()
        val result = service.persist(conversion(bodies = 2, subPerBody = 3))
        assertEquals(SectionValidationResult.Valid, result)
    }

    @Test
    fun persist_atomicityIfSubPointPersistFails_nothingIsPersisted() = runBlocking {
        val (service, sections, subPoints) = setup()
        // Conteúdo anterior (A) persistido com sucesso.
        service.persist(conversion(prefix = "a"))
        val sectionsBefore = sections.snapshot()
        val subPointsBefore = subPoints.snapshot()

        // Nova tentativa (B) falha no upsert de sub-pontos.
        subPoints.failOnUpsertAll = true
        try {
            service.persist(conversion(prefix = "b"))
            fail("Esperava IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue("mensagem: ${e.message}", (e.message ?: "").contains("falha simulada"))
        }

        // Rollback: o banco continua exatamente com o conteúdo A.
        assertEquals(sectionsBefore, sections.rows)
        assertEquals(subPointsBefore, subPoints.rows)
        assertTrue(sections.rows.none { it.id.startsWith("b-") })
        assertTrue(subPoints.rows.none { it.id.startsWith("b-") })
    }
}
