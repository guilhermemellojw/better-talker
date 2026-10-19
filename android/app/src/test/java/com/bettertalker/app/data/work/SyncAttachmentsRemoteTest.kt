package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fonte do anexo no 2º aparelho: binário não sobe, `sourceUrl` sim. */
class SyncAttachmentsRemoteTest {

    private class FakeAttachmentDao(var rows: List<AttachmentEntity> = emptyList()) : AttachmentDao {
        override fun observe(): Flow<List<AttachmentEntity>> = flowOf(rows)
        override fun observeForNote(noteId: String): Flow<List<AttachmentEntity>> =
            flowOf(rows.filter { it.noteId == noteId })
        override suspend fun all(): List<AttachmentEntity> = rows
        override suspend fun get(id: String): AttachmentEntity? = rows.firstOrNull { it.id == id }
        override suspend fun upsert(a: AttachmentEntity) {
            rows = rows.filter { it.id != a.id } + a
        }
        override suspend fun update(a: AttachmentEntity) {
            rows = rows.filter { it.id != a.id } + a
        }
        override suspend fun setIndexed(id: String, indexed: Boolean) {}
        override suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?) {}
        override suspend fun setBaseSlot(id: String, slot: String?) {}
        override suspend fun setNote(id: String, noteId: String?) {}
        override suspend fun baseReady(slot: String): AttachmentEntity? =
            rows.firstOrNull { it.baseSlot == slot && it.indexed }
        override suspend fun delete(id: String) {
            rows = rows.filter { it.id != id }
        }
        override suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?) {}
    }

    private fun attachment(id: String, sourceUrl: String?) = AttachmentEntity(
        id = id,
        noteId = null,
        fileName = "w25.01.pdf",
        kind = "pdf",
        sizeBytes = 42L,
        appPath = "/tmp/$id.pdf",
        indexed = true,
        addedAt = 10L,
        status = "ready",
        sourceUrl = sourceUrl,
    )

    @Test
    fun sourceUrl_sobeEmA_e_chegaEmB_comStatusMissing() = runBlocking {
        val remote = FakeSyncRemote()
        val source = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS?pub=w&fileformat=PDF"
        syncAttachments(
            remote,
            FakeAttachmentDao(listOf(attachment("a1", source))),
            FakeTombstoneDao(),
        )

        val deviceB = FakeAttachmentDao()
        syncAttachments(remote, deviceB, FakeTombstoneDao())

        val pulled = deviceB.get("a1")!!
        assertEquals(source, pulled.sourceUrl)
        assertEquals("missing", pulled.status)
        assertTrue(pulled.appPath.isBlank())
    }

    @Test
    fun sourceUrl_completaMetadadoLocalAntigoSemUrl() = runBlocking {
        val remote = FakeSyncRemote()
        val source = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS?pub=w&fileformat=PDF"
        remote.set("attachments", "a1", mapOf("fileName" to "w25.01.pdf", "sourceUrl" to source))
        val oldLocal = FakeAttachmentDao(listOf(attachment("a1", null)))

        syncAttachments(remote, oldLocal, FakeTombstoneDao())

        assertEquals(source, oldLocal.get("a1")!!.sourceUrl)
    }

    @Test
    fun sourceUrl_atualizaDocAntigoQueNaoTinhaUrl() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("attachments", "a1", mapOf("fileName" to "w25.01.pdf", "kind" to "pdf"))
        val source = "https://www.jw.org/pt/biblioteca/revistas/"

        syncAttachments(
            remote,
            FakeAttachmentDao(listOf(attachment("a1", source))),
            FakeTombstoneDao(),
        )

        assertEquals(source, remote.doc("attachments", "a1")!!["sourceUrl"])
    }

    @Test
    fun tombstoneRemotoDeAnexo_apagaMetadadosLocais() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("tombstones", "a1", mapOf("type" to "attachment", "deletedAt" to 20L))
        val remoteTombs = remote.getAll("tombstones")
        val dao = FakeAttachmentDao(listOf(attachment("a1", "https://example.test/a.pdf")))

        syncAttachments(remote, dao, FakeTombstoneDao(), remoteTombs)

        assertNull(dao.get("a1"))
        assertNull(remote.doc("attachments", "a1"))
    }
}
