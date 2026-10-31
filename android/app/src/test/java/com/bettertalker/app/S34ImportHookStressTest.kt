package com.bettertalker.app

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.repo.S34OutlineRepository
import com.bettertalker.app.data.s34.S34ImportHook
import com.bettertalker.app.data.util.S34Detector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class S34ImportHookStressTest {
    private class FakeS34Dao : S34Dao {
        val outlines = mutableMapOf<String, S34OutlineEntity>()
        val sections = mutableMapOf<String, S34SectionEntity>()
        val subs = mutableMapOf<String, S34SubsectionEntity>()
        val refs = mutableMapOf<String, S34ReferenceEntity>()
        override suspend fun putOutline(o: S34OutlineEntity) { outlines[o.id] = o }
        override suspend fun putSections(list: List<S34SectionEntity>) { list.forEach { sections[it.id] = it } }
        override suspend fun putSubsections(list: List<S34SubsectionEntity>) { list.forEach { subs[it.id] = it } }
        override suspend fun putReferences(list: List<S34ReferenceEntity>) { list.forEach { refs[it.id] = it } }
        override suspend fun outlineById(id: String) = outlines[id]
        override suspend fun outlineBySource(sourceId: String) = outlines.values.firstOrNull { it.sourceAttachmentId == sourceId }
        override suspend fun sectionsOf(outlineId: String) = sections.values.filter { it.outlineId == outlineId }.sortedBy { it.order }
        override suspend fun subsectionsOf(sectionIds: List<String>) = subs.values.filter { it.sectionId in sectionIds }.sortedBy { it.sectionId }
        override suspend fun referencesOf(outlineId: String) = refs.values.filter { it.outlineId == outlineId }.sortedBy { it.order }
        override suspend fun deleteRefsOf(outlineId: String) {}
        override suspend fun deleteSubsOf(sectionIds: List<String>) {}
        override suspend fun deleteSectionsOf(outlineId: String) {}
        override suspend fun deleteOutline(id: String) { outlines.remove(id) }
        override suspend fun deleteOutlineBySource(sourceId: String) {}
    }

    private lateinit var dao: FakeS34Dao
    private lateinit var repo: com.bettertalker.app.data.repo.S34OutlineRepository
    private val silent: (String, String) -> Unit = { _, _ -> }

    @Before
    fun setup() {
        dao = FakeS34Dao()
        repo = com.bettertalker.app.data.repo.S34OutlineRepository(dao)
    }

    private fun importar(attachmentId: String, text: String) =
        runBlocking { S34ImportHook.onExtracted(dao, attachmentId, text, silent) }

    @Test
    fun importa40EsboçosIgual() {
        val textoBase = S34Fixture.TEXT

        for (i in 1..40) {
            val texto = textoBase + "\nNota de teste $i"
            importar("att-stress-iso-$i", texto)
        }

        for (i in 1..40) {
            val doc = runBlocking { repo.getBySource("att-stress-iso-$i") }
            assertNotNull("Esboço $i deve estar persistido", doc)
            assertEquals("Como fortalecer a fé", doc!!.title)
            assertTrue(doc.sections.isNotEmpty())
        }

        val outlineIds = dao.outlines.values.map { it.id }.toSet()
        assertEquals(40, outlineIds.size)
    }
}
