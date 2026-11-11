package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.S34Dao
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncS34RemoteTest {

    private class FakeS34Dao(
        outlines: List<S34OutlineEntity> = emptyList(),
        sections: List<S34SectionEntity> = emptyList(),
        subsections: List<S34SubsectionEntity> = emptyList(),
        references: List<S34ReferenceEntity> = emptyList(),
    ) : S34Dao {
        var outlines = outlines.toMutableList()
        var sections = sections.toMutableList()
        var subsections = subsections.toMutableList()
        var references = references.toMutableList()

        override suspend fun putOutline(o: S34OutlineEntity) {
            outlines = outlines.filter { it.id != o.id }.toMutableList().also { it += o }
        }

        override suspend fun putSections(list: List<S34SectionEntity>) {
            val ids = list.map { it.id }.toSet()
            sections = sections.filter { it.id !in ids }.toMutableList().also { it += list }
        }

        override suspend fun putSubsections(list: List<S34SubsectionEntity>) {
            val ids = list.map { it.id }.toSet()
            subsections = subsections.filter { it.id !in ids }.toMutableList().also { it += list }
        }

        override suspend fun putReferences(list: List<S34ReferenceEntity>) {
            val ids = list.map { it.id }.toSet()
            references = references.filter { it.id !in ids }.toMutableList().also { it += list }
        }

        override suspend fun allOutlines(): List<S34OutlineEntity> = outlines.toList()
        override suspend fun allSections(): List<S34SectionEntity> = sections.toList()
        override suspend fun allSubsections(): List<S34SubsectionEntity> = subsections.toList()
        override suspend fun allReferences(): List<S34ReferenceEntity> = references.toList()

        override suspend fun outlineById(id: String): S34OutlineEntity? =
            outlines.firstOrNull { it.id == id }

        override suspend fun outlineBySource(sourceId: String): S34OutlineEntity? =
            outlines.firstOrNull { it.sourceAttachmentId == sourceId }

        override suspend fun sectionsOf(outlineId: String): List<S34SectionEntity> =
            sections.filter { it.outlineId == outlineId }.sortedBy { it.order }

        override suspend fun subsectionsOf(sectionIds: List<String>): List<S34SubsectionEntity> =
            subsections.filter { it.sectionId in sectionIds }

        override suspend fun referencesOf(outlineId: String): List<S34ReferenceEntity> =
            references.filter { it.outlineId == outlineId }.sortedBy { it.order }

        override suspend fun deleteRefsOf(outlineId: String) {
            references = references.filter { it.outlineId != outlineId }.toMutableList()
        }

        override suspend fun deleteSubsOf(sectionIds: List<String>) {
            subsections = subsections.filter { it.sectionId !in sectionIds }.toMutableList()
        }

        override suspend fun deleteSectionsOf(outlineId: String) {
            sections = sections.filter { it.outlineId != outlineId }.toMutableList()
        }

        override suspend fun deleteOutline(id: String) {
            outlines = outlines.filter { it.id != id }.toMutableList()
        }

        override suspend fun deleteOutlineBySource(sourceId: String) {
            outlines = outlines.filter { it.sourceAttachmentId != sourceId }.toMutableList()
        }
    }

    private fun outline(id: String, source: String, updatedAt: Long) = S34OutlineEntity(
        id = id,
        sourceAttachmentId = source,
        symbol = "w25.01",
        title = "S-34 teste",
        objective = "objetivo",
        headerLinesJson = "[\"linha\"]",
        createdAt = 1L,
        updatedAt = updatedAt,
        parserVersion = 2,
    )

    private fun section(outlineId: String, id: String, order: Int = 0) = S34SectionEntity(
        id = id,
        outlineId = outlineId,
        order = order,
        title = "Ponto $order",
        content = "conteúdo $order",
        minutes = 4,
        sourceLine = 10 + order,
    )

    private fun subsection(sectionId: String, id: String) = S34SubsectionEntity(
        id = id,
        sectionId = sectionId,
        order = 0,
        content = "subconteúdo",
        sourceLine = 20,
    )

    private fun reference(outlineId: String, sectionId: String?, id: String) = S34ReferenceEntity(
        id = id,
        outlineId = outlineId,
        sectionId = sectionId,
        subsectionId = null,
        order = 0,
        type = "BIBLE",
        rawText = "João 3:16",
        normalizedReference = "joao 3 16",
        sourceLine = 30,
        book = "João",
        bookNorm = "joao",
        chapter = 3,
        verse = 16,
        pubKind = null,
        pubKey = null,
        pubLabel = null,
        editionKey = null,
    )

    @Test
    fun pushPullRoundTrip() = runBlocking {
        val remote = FakeSyncRemote()
        val a = FakeS34Dao(
            outlines = listOf(outline("o1", "att1", 10)),
            sections = listOf(section("o1", "o1:sec-0", 0)),
            subsections = listOf(subsection("o1:sec-0", "o1:sec-0-sub-0")),
            references = listOf(reference("o1", "o1:sec-0", "o1-ref-0")),
        )
        syncS34(remote, a)

        val b = FakeS34Dao()
        syncS34(remote, b)

        assertEquals("o1", b.allOutlines().single().id)
        assertEquals("att1", b.allOutlines().single().sourceAttachmentId)
        assertEquals(1, b.allSections().size)
        assertEquals(1, b.allSubsections().size)
        assertEquals(1, b.allReferences().size)
        assertEquals("João 3:16", b.allReferences().single().rawText)
    }

    @Test
    fun lwwPuxaRemotoMaisNovo() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("s34Outlines", "o1", mapOf(
            "sourceAttachmentId" to "att1",
            "title" to "remoto",
            "updatedAt" to 20L,
        ))
        val local = FakeS34Dao(outlines = listOf(outline("o1", "att1", 10)))

        syncS34(remote, local)

        assertEquals(20L, local.allOutlines().single().updatedAt)
        assertEquals("remoto", local.allOutlines().single().title)
    }

    @Test
    fun lwwEmpurraLocalMaisNovo() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("s34Outlines", "o1", mapOf(
            "sourceAttachmentId" to "att1",
            "title" to "remoto",
            "updatedAt" to 10L,
        ))
        val local = FakeS34Dao(outlines = listOf(outline("o1", "att1", 20)))

        syncS34(remote, local)

        assertEquals(20L, remote.doc("s34Outlines", "o1")!!["updatedAt"])
        assertEquals("S-34 teste", remote.doc("s34Outlines", "o1")!!["title"])
    }

    @Test
    fun substituicaoPorFonteRemoveIdAntigo() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("s34Outlines", "old", mapOf("sourceAttachmentId" to "att1", "updatedAt" to 10L))
        remote.set("s34Sections", "old:sec-0", mapOf(
            "id" to "old:sec-0", "outlineId" to "old",
        ))
        val local = FakeS34Dao(
            outlines = listOf(outline("new", "att1", 20)),
            sections = listOf(section("new", "new:sec-0")),
        )

        syncS34(remote, local)

        assertNull(remote.doc("s34Outlines", "old"))
        assertNull(remote.doc("s34Sections", "old:sec-0"))
        assertEquals("new", remote.doc("s34Outlines", "new")!!["id"])
    }

    @Test
    fun tombstoneDeAttachmentRemoveS34DosDoisLados() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("s34Outlines", "o1", mapOf("sourceAttachmentId" to "att1", "updatedAt" to 10L))
        val local = FakeS34Dao(outlines = listOf(outline("o1", "att1", 10)))

        syncS34(
            remote = remote,
            s34Dao = local,
            remoteTombs = mapOf("att1" to mapOf("type" to "attachment")),
            attachmentTombIds = setOf("att1"),
        )

        assertNull(remote.doc("s34Outlines", "o1"))
        assertTrue(local.allOutlines().isEmpty())
    }

    @Test
    fun puxaS34RemotoMesmoComAnexoAusente() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("s34Outlines", "o1", mapOf(
            "sourceAttachmentId" to "att-ausente",
            "title" to "sem anexo",
            "updatedAt" to 10L,
        ))
        val local = FakeS34Dao()

        syncS34(remote, local)

        assertEquals("att-ausente", local.allOutlines().single().sourceAttachmentId)
    }

    @Test
    fun modoDownloadNaoEmpurraLocalSemRemoto() = runBlocking {
        val remote = FakeSyncRemote()
        val local = FakeS34Dao(outlines = listOf(outline("o1", "att1", 10)))

        syncS34(remote, local, pushEnabled = false)

        assertNull(remote.doc("s34Outlines", "o1"))
        assertEquals(1, local.allOutlines().size)
    }
}
