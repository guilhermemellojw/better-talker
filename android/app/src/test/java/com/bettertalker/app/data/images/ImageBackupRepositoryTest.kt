package com.bettertalker.app.data.images

import com.bettertalker.app.data.db.ImageBackupEntity
import com.bettertalker.app.data.db.NoteImageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageBackupRepositoryTest {

    private val files = mutableListOf<File>()

    @After
    fun cleanup() {
        files.forEach { it.delete() }
    }

    private fun image(
        id: String = "img-1",
        noteId: String = "note-1",
        mime: String = "image/jpeg",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ): NoteImageEntity {
        val file = File.createTempFile("bt-image-", ".bin")
        file.writeBytes(bytes)
        files += file
        return NoteImageEntity(
            id = id,
            noteId = noteId,
            path = file.absolutePath,
            mime = mime,
            addedAt = 1L,
        )
    }

    @Test
    fun semLoginFicaPendenteESemUpload() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        val uploader = FakeUploader()
        val repo = ImageBackupRepository(
            store = store,
            uploader = uploader,
            compressor = FakeCompressor(),
            uidProvider = { null },
        )
        assertEquals(ImageBackupOutcome.Deferred, repo.upload("img-1"))
        assertTrue(uploader.uploads.isEmpty())
        assertNull(store.backups["img-1"])
    }

    @Test
    fun jpegCompactaEEntregaWebp() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        val uploader = FakeUploader()
        val compressor = FakeCompressor(byteArrayOf(9, 9, 9, 9))
        val repo = ImageBackupRepository(
            store = store,
            uploader = uploader,
            compressor = compressor,
            uidProvider = { "uid-1" },
            clock = { 123L },
        )
        val outcome = repo.upload("img-1")
        assertTrue(outcome is ImageBackupOutcome.Uploaded)
        assertEquals(1, compressor.calls)
        assertEquals(1, uploader.uploads.size)
        assertEquals(
            "users/uid-1/images/note-1/img-1.webp",
            uploader.uploads.single().path,
        )
        assertEquals("image/webp", uploader.uploads.single().contentType)
        val state = store.backups["img-1"]!!
        assertEquals("uploaded", state.state)
        assertEquals(4L, state.sizeBytes)
        assertEquals(123L, state.uploadedAt)
        assertNotNull(state.sha256)
    }

    @Test
    fun gifPequenoMantemAnimacaoSemRecomprimir() = runBlocking {
        val store = FakeStore()
        val gifBytes = ByteArray(100) { 7 }
        store.images["img-1"] = image(mime = "image/gif", bytes = gifBytes)
        val uploader = FakeUploader()
        val compressor = FakeCompressor(byteArrayOf(1))
        val repo = ImageBackupRepository(
            store = store,
            uploader = uploader,
            compressor = compressor,
            uidProvider = { "uid-1" },
        )
        repo.upload("img-1")
        assertEquals(0, compressor.calls)
        assertEquals("image/gif", uploader.uploads.single().contentType)
        assertTrue(uploader.uploads.single().path.endsWith(".gif"))
        assertTrue(uploader.uploads.single().bytes.contentEquals(gifBytes))
    }

    @Test
    fun falhaDoUploaderMarcaFailed() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        val uploader = FakeUploader(fail = true)
        val repo = ImageBackupRepository(
            store = store,
            uploader = uploader,
            compressor = FakeCompressor(byteArrayOf(1, 2)),
            uidProvider = { "uid-1" },
        )
        val outcome = repo.upload("img-1")
        assertTrue(outcome is ImageBackupOutcome.Failed)
        assertTrue((outcome as ImageBackupOutcome.Failed).retryable)
        assertEquals("failed", store.backups["img-1"]!!.state)
        assertEquals(1, store.backups["img-1"]!!.attempts)
    }

    @Test
    fun erro404DoStorageViraMensagemAcionavel() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        val uploader = FakeUploader(fail = true, failMessage = "Not Found.")
        val repo = ImageBackupRepository(
            store = store,
            uploader = uploader,
            compressor = FakeCompressor(byteArrayOf(1, 2)),
            uidProvider = { "uid-1" },
        )
        val outcome = repo.upload("img-1")
        assertTrue(outcome is ImageBackupOutcome.Failed)
        assertTrue(
            (outcome as ImageBackupOutcome.Failed).message.contains("não está ativado")
        )
        assertEquals(
            "failed",
            store.backups["img-1"]!!.state,
        )
    }

    @Test
    fun uploadJaFeitoNaoSobeDeNovo() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        store.backups["img-1"] = ImageBackupEntity(
            imageId = "img-1",
            state = "uploaded",
            cloudPath = "users/uid-1/images/note-1/img-1.webp",
            sizeBytes = 42,
            updatedAt = 1,
        )
        val uploader = FakeUploader()
        val repo = ImageBackupRepository(
            store = store,
            uploader = uploader,
            compressor = FakeCompressor(byteArrayOf(1)),
            uidProvider = { "uid-1" },
        )
        assertTrue(repo.upload("img-1") is ImageBackupOutcome.Uploaded)
        assertTrue(uploader.uploads.isEmpty())
    }

    @Test
    fun deleteCloudApagaObjetoERegistro() = runBlocking {
        val store = FakeStore()
        store.backups["img-1"] = ImageBackupEntity(
            imageId = "img-1",
            state = "uploaded",
            cloudPath = "users/uid-1/images/note-1/img-1.webp",
            sizeBytes = 42,
            updatedAt = 1,
        )
        val uploader = FakeUploader()
        val repo = ImageBackupRepository(
            store = store,
            uploader = uploader,
            compressor = FakeCompressor(byteArrayOf(1)),
            uidProvider = { "uid-1" },
        )
        repo.deleteCloud("img-1")
        assertEquals(
            listOf("users/uid-1/images/note-1/img-1.webp"),
            uploader.deleted,
        )
        assertNull(store.backups["img-1"])
    }

    private class FakeStore : ImageBackupStore {
        val images = mutableMapOf<String, NoteImageEntity>()
        val backups = mutableMapOf<String, ImageBackupEntity>()

        override suspend fun image(imageId: String): NoteImageEntity? = images[imageId]
        override suspend fun backup(imageId: String): ImageBackupEntity? = backups[imageId]
        override suspend fun save(backup: ImageBackupEntity) { backups[backup.imageId] = backup }
        override suspend fun deleteBackup(imageId: String) { backups.remove(imageId) }
        override suspend fun imagesForNote(noteId: String): List<NoteImageEntity> =
            images.values.filter { it.noteId == noteId }
    }

    private class FakeUploader(
        private val fail: Boolean = false,
        private val failMessage: String = "rede caiu",
    ) : ImageUploader {
        data class Upload(val path: String, val bytes: ByteArray, val contentType: String)
        val uploads = mutableListOf<Upload>()
        val deleted = mutableListOf<String>()

        override suspend fun upload(path: String, bytes: ByteArray, contentType: String) {
            if (fail) throw IllegalStateException(failMessage)
            uploads += Upload(path, bytes, contentType)
        }

        override suspend fun delete(path: String) {
            deleted += path
        }
    }

    private class FakeCompressor(
        private val output: ByteArray = byteArrayOf(1),
    ) : ImageCompressor {
        var calls = 0
        override fun compress(file: File, mime: String): CompressedImage {
            calls++
            return CompressedImage(output, "image/webp", "webp")
        }
    }
}
