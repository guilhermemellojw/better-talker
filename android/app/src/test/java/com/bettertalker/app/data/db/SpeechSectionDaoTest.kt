package com.bettertalker.app.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSpeechSectionDao(var rows: List<SpeechSectionEntity> = emptyList()) : SpeechSectionDao {
    override suspend fun forNote(noteId: String): List<SpeechSectionEntity> =
        rows.filter { it.noteId == noteId }.sortedBy { it.order }
    override suspend fun get(id: String): SpeechSectionEntity? =
        rows.firstOrNull { it.id == id }
    override suspend fun upsert(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id } + section
    }
    override suspend fun upsertAll(sections: List<SpeechSectionEntity>) {
        sections.forEach { upsert(it) }
    }
    override suspend fun delete(section: SpeechSectionEntity) {
        rows = rows.filter { it.id != section.id }
    }
    override suspend fun deleteForNote(noteId: String) {
        rows = rows.filter { it.noteId != noteId }
    }
    // 3.2.5a: snapshot única (fakes destes testes não observam).
    override fun observeForNote(noteId: String): Flow<List<SpeechSectionEntity>> =
        flowOf(rows.filter { it.noteId == noteId }.sortedBy { it.order })
}

class SpeechSectionDaoTest {

    private fun entity(id: String, note: String, order: Int) = SpeechSectionEntity(
        id = id, noteId = note, order = order, role = "BODY",
        title = "T $id", minutes = 5, contentHtml = "<p>$id</p>",
        bibleRefsJson = "[]", publicationRefsJson = "[]",
        methodPrinciple = null, createdAt = 1L, updatedAt = 2L,
    )

    @Test
    fun upsert_thenGet_returnsEntity() = runBlocking {
        val dao = FakeSpeechSectionDao()
        dao.upsert(entity("s1", "n1", 0))
        assertEquals("s1", dao.get("s1")?.id)
        assertNull(dao.get("nope"))
    }

    @Test
    fun forNote_ordersByOrder() = runBlocking {
        val dao = FakeSpeechSectionDao()
        dao.upsert(entity("s2", "n1", 1))
        dao.upsert(entity("s1", "n1", 0))
        dao.upsert(entity("s9", "n2", 0))
        assertEquals(listOf("s1", "s2"), dao.forNote("n1").map { it.id })
    }

    @Test
    fun upsertAll_insertsAll() = runBlocking {
        val dao = FakeSpeechSectionDao()
        dao.upsertAll(listOf(entity("s1", "n1", 0), entity("s2", "n1", 1)))
        assertEquals(2, dao.forNote("n1").size)
    }

    @Test
    fun deleteForNote_removesAllForNote() = runBlocking {
        val dao = FakeSpeechSectionDao()
        dao.upsertAll(listOf(entity("s1", "n1", 0), entity("s2", "n2", 0)))
        dao.deleteForNote("n1")
        assertTrue(dao.forNote("n1").isEmpty())
        assertEquals(1, dao.forNote("n2").size)
    }
}
