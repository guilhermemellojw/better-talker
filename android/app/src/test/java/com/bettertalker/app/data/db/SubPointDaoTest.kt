package com.bettertalker.app.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSubPointDao(var rows: List<SubPointEntity> = emptyList()) : SubPointDao {
    override suspend fun forSection(sectionId: String): List<SubPointEntity> =
        rows.filter { it.sectionId == sectionId }.sortedBy { it.order }
    override suspend fun get(id: String): SubPointEntity? =
        rows.firstOrNull { it.id == id }
    override suspend fun forSections(sectionIds: List<String>): List<SubPointEntity> =
        rows.filter { it.sectionId in sectionIds }
            .sortedWith(compareBy({ it.sectionId }, { it.order }))
    override suspend fun upsert(subPoint: SubPointEntity) {
        rows = rows.filter { it.id != subPoint.id } + subPoint
    }
    override suspend fun upsertAll(subPoints: List<SubPointEntity>) {
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
}

class SubPointDaoTest {

    private fun entity(id: String, section: String, order: Int) = SubPointEntity(
        id = id, sectionId = section, order = order, outlineText = "Ponto $id",
        bibleRefsJson = "[]", publicationRefsJson = "[]",
        instruction = null, developedHtml = "", createdAt = 1L, updatedAt = 2L,
    )

    @Test
    fun upsert_thenGet_returnsEntity() = runBlocking {
        val dao = FakeSubPointDao()
        dao.upsert(entity("sp1", "sec1", 0))
        assertEquals("sp1", dao.get("sp1")?.id)
        assertNull(dao.get("nope"))
    }

    @Test
    fun forSection_ordersByOrder() = runBlocking {
        val dao = FakeSubPointDao()
        dao.upsert(entity("sp2", "sec1", 1))
        dao.upsert(entity("sp1", "sec1", 0))
        dao.upsert(entity("sp9", "sec2", 0))
        assertEquals(listOf("sp1", "sp2"), dao.forSection("sec1").map { it.id })
    }

    @Test
    fun forSections_returnsAllForMultipleSections() = runBlocking {
        val dao = FakeSubPointDao()
        dao.upsertAll(listOf(
            entity("sp1", "sec1", 0),
            entity("sp2", "sec1", 1),
            entity("sp3", "sec2", 0),
            entity("sp9", "sec3", 0),
        ))
        assertEquals(listOf("sp1", "sp2", "sp3"), dao.forSections(listOf("sec1", "sec2")).map { it.id })
    }

    @Test
    fun upsertAll_insertsAll() = runBlocking {
        val dao = FakeSubPointDao()
        dao.upsertAll(listOf(entity("sp1", "sec1", 0), entity("sp2", "sec1", 1)))
        assertEquals(2, dao.forSection("sec1").size)
    }

    @Test
    fun deleteForSection_removesAllForSection() = runBlocking {
        val dao = FakeSubPointDao()
        dao.upsertAll(listOf(entity("sp1", "sec1", 0), entity("sp2", "sec2", 0)))
        dao.deleteForSection("sec1")
        assertTrue(dao.forSection("sec1").isEmpty())
        assertEquals(1, dao.forSection("sec2").size)
    }
}
