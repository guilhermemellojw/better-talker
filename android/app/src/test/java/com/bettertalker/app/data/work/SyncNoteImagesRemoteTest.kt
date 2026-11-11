package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.ImageBackupDao
import com.bettertalker.app.data.db.ImageBackupEntity
import com.bettertalker.app.data.db.NoteImageDao
import com.bettertalker.app.data.db.NoteImageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncNoteImagesRemoteTest {

    private class FakeNoteImageDao(rows: List<NoteImageEntity> = emptyList()) : NoteImageDao {
        var rows = rows.toMutableList()
        override fun observeForNote(noteId: String): Flow<List<NoteImageEntity>> =
            flowOf(rows.filter { it.noteId == noteId })

        override suspend fun forNote(noteId: String): List<NoteImageEntity> =
            rows.filter { it.noteId == noteId }

        override suspend fun forSection(sectionId: String): List<NoteImageEntity> =
            rows.filter { it.sectionId == sectionId }

        override suspend fun forSections(sectionIds: List<String>): List<NoteImageEntity> =
            rows.filter { it.sectionId in sectionIds }

        override suspend fun get(id: String): NoteImageEntity? = rows.firstOrNull { it.id == id }
        override suspend fun upsert(image: NoteImageEntity) {
            rows = rows.filter { it.id != image.id }.toMutableList().also { it += image }
        }

        override suspend fun delete(id: String) {
            rows = rows.filter { it.id != id }.toMutableList()
        }

        override suspend fun updateCaption(id: String, caption: String) {
            rows = rows.map { if (it.id == id) it.copy(caption = caption) else it }.toMutableList()
        }

        override suspend fun updateKind(id: String, kind: String) {
            rows = rows.map { if (it.id == id) it.copy(kind = kind) else it }.toMutableList()
        }

        override suspend fun updateOrder(id: String, order: Int) {
            rows = rows.map { if (it.id == id) it.copy(order = order) else it }.toMutableList()
        }

        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override suspend fun all(): List<NoteImageEntity> = rows.toList()
        override suspend fun pendingForBackup(): List<NoteImageEntity> = rows
    }

    private class FakeImageBackupDao(rows: List<ImageBackupEntity> = emptyList()) : ImageBackupDao {
        var rows = rows.toMutableList()
        override suspend fun get(imageId: String): ImageBackupEntity? =
            rows.firstOrNull { it.imageId == imageId }

        override suspend fun upsert(row: ImageBackupEntity) {
            rows = rows.filter { it.imageId != row.imageId }.toMutableList().also { it += row }
        }

        override suspend fun delete(imageId: String) {
            rows = rows.filter { it.imageId != imageId }.toMutableList()
        }

        override fun observeUploadedCount(): Flow<Int> =
            flowOf(rows.count { it.state == "uploaded" })
        override fun observeFailedCount(): Flow<Int> = flowOf(rows.count { it.state == "failed" })
        override fun observeUploadedBytes(): Flow<Long> =
            flowOf(rows.filter { it.state == "uploaded" }.sumOf { it.sizeBytes })
        override fun observeLastError(): Flow<String?> = flowOf(null)
        override fun observeDeletedCount(): Flow<Int> = flowOf(rows.count { it.state == "deleted" })
        override suspend fun pendingDeletes(): List<ImageBackupEntity> =
            rows.filter { it.state == "deleted" }
        override suspend fun clearAll() {
            rows.clear()
        }
    }

    private fun noteImage(
        id: String = "img1",
        noteId: String = "n1",
        sectionId: String? = "s1",
        path: String = "",
        caption: String = "Legenda",
    ) = NoteImageEntity(
        id = id, noteId = noteId, sectionId = sectionId, path = path,
        mime = "image/webp", caption = caption, kind = "illustration",
        order = 0, addedAt = 1L,
    )

    private fun backup(
        imageId: String = "img1",
        state: String = "uploaded",
        updatedAt: Long = 10L,
        cloudPath: String? = "users/u/images/n1/img1.webp",
    ) = ImageBackupEntity(
        imageId = imageId, state = state, cloudPath = cloudPath,
        sizeBytes = 3L, sha256 = "abc", uploadedAt = 10L,
        updatedAt = updatedAt, uid = "u",
    )

    private suspend fun tool(
        remote: FakeSyncRemote,
        images: FakeNoteImageDao,
        backups: FakeImageBackupDao,
        notes: FakeNoteDao = FakeNoteDao(listOf(note())),
        sections: FakeSectionDao = FakeSectionDao(listOf(section())),
        tombs: FakeTombstoneDao = FakeTombstoneDao(),
        pushEnabled: Boolean = true,
    ): Boolean = syncNoteImages(
        remote = remote,
        noteImageDao = images,
        imageBackupDao = backups,
        noteDao = notes,
        sectionDao = sections,
        tombstoneDao = tombs,
        remoteTombs = emptyMap(),
        pushEnabled = pushEnabled,
        uid = "u",
    )

    private fun note() = com.bettertalker.app.data.db.NoteEntity(
        id = "n1", title = "Nota", mdText = "", plainText = "",
        folderId = null, colorArgb = 0, pinned = false, trashed = false,
        createdAt = 1, updatedAt = 1,
    )

    private fun section() = com.bettertalker.app.data.db.SpeechSectionEntity(
        id = "s1", noteId = "n1", order = 0, role = "BODY", title = "Ponto",
        minutes = 5, contentHtml = "", bibleRefsJson = "[]",
        publicationRefsJson = "[]", methodPrinciple = null,
        createdAt = 1, updatedAt = 1,
    )

    @Test
    fun uploadLocalViraManifestoSemPath() = runBlocking {
        val file = File.createTempFile("bt-img", ".webp").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val remote = FakeSyncRemote()
        val images = FakeNoteImageDao(listOf(noteImage(path = file.absolutePath)))
        val backups = FakeImageBackupDao(listOf(backup()))

        tool(remote, images, backups)

        val doc = remote.doc("noteImages", "img1")!!
        assertEquals("n1", doc["noteId"])
        assertEquals("users/u/images/n1/img1.webp", doc["cloudPath"])
        assertFalse(doc.containsKey("path"))
        assertEquals("uploaded", doc["state"])
    }

    @Test
    fun pullCriaPlaceholderERestoreFlag() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("noteImages", "img1", mapOf(
            "noteId" to "n1", "sectionId" to "s1", "caption" to "Legenda remota",
            "kind" to "chart", "order" to 2, "mime" to "image/webp",
            "cloudPath" to "users/u/images/n1/img1.webp", "sha256" to "abc",
            "sizeBytes" to 3L, "state" to "uploaded", "updatedAt" to 20L,
            "addedAt" to 1L,
        ))
        val images = FakeNoteImageDao()
        val backups = FakeImageBackupDao()

        val needsRestore = tool(remote, images, backups)

        assertTrue(needsRestore)
        assertEquals("", images.get("img1")!!.path)
        assertEquals("Legenda remota", images.get("img1")!!.caption)
        assertEquals("chart", images.get("img1")!!.kind)
        assertEquals(2, images.get("img1")!!.order)
        assertEquals("uploaded", backups.get("img1")!!.state)
    }

    @Test
    fun pullIgnoraNotaAusente() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("noteImages", "img1", mapOf(
            "noteId" to "note-ausente", "state" to "uploaded",
            "updatedAt" to 20L, "cloudPath" to "users/u/images/x/img1.webp",
        ))
        val images = FakeNoteImageDao()

        val needsRestore = tool(remote, images, FakeImageBackupDao())

        assertFalse(needsRestore)
        assertNull(images.get("img1"))
    }

    @Test
    fun pullComSecaoRemovidaZeraSectionId() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("noteImages", "img1", mapOf(
            "noteId" to "n1", "sectionId" to "sumiu", "state" to "uploaded",
            "updatedAt" to 20L, "cloudPath" to "users/u/images/n1/img1.webp",
        ))
        val images = FakeNoteImageDao()

        tool(remote, images, FakeImageBackupDao())

        assertNull(images.get("img1")!!.sectionId)
    }

    @Test
    fun lwwRemotoAtualizaMetadadoPreservandoPath() = runBlocking {
        val file = File.createTempFile("bt-img", ".webp").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val remote = FakeSyncRemote()
        remote.set("noteImages", "img1", mapOf(
            "noteId" to "n1", "caption" to "remota", "kind" to "photo",
            "order" to 3, "state" to "uploaded", "updatedAt" to 20L,
            "cloudPath" to "users/u/images/n1/img1.webp",
        ))
        val images = FakeNoteImageDao(listOf(noteImage(path = file.absolutePath)))
        val backups = FakeImageBackupDao(listOf(backup(updatedAt = 10L)))

        tool(remote, images, backups)

        assertEquals("remota", images.get("img1")!!.caption)
        assertEquals("photo", images.get("img1")!!.kind)
        assertEquals(file.absolutePath, images.get("img1")!!.path)
    }

    @Test
    fun lwwLocalMaisNovoEmpurraManifesto() = runBlocking {
        val file = File.createTempFile("bt-img", ".webp").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val remote = FakeSyncRemote()
        remote.set("noteImages", "img1", mapOf(
            "noteId" to "n1", "caption" to "antiga", "state" to "uploaded",
            "updatedAt" to 10L, "cloudPath" to "users/u/images/n1/img1.webp",
        ))
        val images = FakeNoteImageDao(listOf(noteImage(path = file.absolutePath, caption = "nova")))
        val backups = FakeImageBackupDao(listOf(backup(updatedAt = 20L)))

        tool(remote, images, backups)

        assertEquals("nova", remote.doc("noteImages", "img1")!!["caption"])
    }

    @Test
    fun arquivoLocalAusenteViraPlaceholder() = runBlocking {
        val remote = FakeSyncRemote()
        remote.set("noteImages", "img1", mapOf(
            "noteId" to "n1", "state" to "uploaded", "updatedAt" to 20L,
            "cloudPath" to "users/u/images/n1/img1.webp",
        ))
        val images = FakeNoteImageDao(listOf(noteImage(path = "/tmp/nao-existe-bt.webp")))
        val backups = FakeImageBackupDao(listOf(backup(updatedAt = 10L)))

        val needsRestore = tool(remote, images, backups)

        assertTrue(needsRestore)
        assertEquals("", images.get("img1")!!.path)
    }

    @Test
    fun tombstoneRemotoApagaImagemArquivoEBackup() = runBlocking {
        val file = File.createTempFile("bt-img", ".webp").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val remote = FakeSyncRemote()
        remote.set("tombstones", "img1", mapOf("type" to "noteImage", "deletedAt" to 30L))
        remote.set("noteImages", "img1", mapOf("noteId" to "n1", "state" to "uploaded"))
        val images = FakeNoteImageDao(listOf(noteImage(path = file.absolutePath)))
        val backups = FakeImageBackupDao(listOf(backup()))
        val tombs = FakeTombstoneDao()

        syncNoteImages(
            remote = remote,
            noteImageDao = images,
            imageBackupDao = backups,
            noteDao = FakeNoteDao(listOf(note())),
            sectionDao = FakeSectionDao(listOf(section())),
            tombstoneDao = tombs,
            remoteTombs = mapOf("img1" to mapOf("type" to "noteImage")),
            pushEnabled = true,
            uid = "u",
        )

        assertNull(images.get("img1"))
        assertNull(backups.get("img1"))
        assertFalse(file.exists())
        assertNull(remote.doc("noteImages", "img1"))
    }
}
