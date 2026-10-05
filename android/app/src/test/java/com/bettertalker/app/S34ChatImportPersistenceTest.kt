package com.bettertalker.app

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.s34.S34ImportHook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 (Bug #8) — import de S-34 (via chat) persiste a estrutura completa
 * (`s34_outlines/sections/subsections/references`), não só o draft legado.
 */
class S34ChatImportPersistenceTest {

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

    @Test
    fun importDeS34PersisteEstruturaCompleta() = runBlocking {
        val dao = FakeS34Dao()
        val out = S34ImportHook.onExtracted(dao, "att-chat-import", S34Fixture.TEXT) { _, _ -> }
        assertTrue("esperava Saved", out is S34ImportHook.Outcome.Saved)
        assertEquals(1, dao.outlines.size)
        assertEquals(3, dao.sections.size)
        assertEquals(2, dao.subs.size)
        assertTrue(dao.refs.size >= 4)
        val saved = out as S34ImportHook.Outcome.Saved
        assertEquals(3, saved.sections)
    }

    @Test
    fun reimporteSubstituiSemLixo() = runBlocking {
        val dao = FakeS34Dao()
        S34ImportHook.onExtracted(dao, "att-1", S34Fixture.TEXT) { _, _ -> }
        S34ImportHook.onExtracted(dao, "att-1", S34Fixture.TEXT) { _, _ -> }
        assertEquals(1, dao.outlines.size)
        assertEquals(3, dao.sections.size)
        assertEquals(2, dao.subs.size)
    }
}
