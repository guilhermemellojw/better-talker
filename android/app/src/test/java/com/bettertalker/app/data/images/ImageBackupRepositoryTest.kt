package com.bettertalker.app.data.images

import com.bettertalker.app.data.db.ImageBackupEntity
import com.bettertalker.app.data.db.NoteImageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageBackupRepositoryTest {

    private val files = mutableListOf<File>()
    private val dirs = mutableListOf<File>()

    @After
    fun cleanup() {
        files.forEach { it.delete() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun image(
        id: String = "img-1",
        noteId: String = "note-1",
        mime: String = "image/jpeg",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        caption: String = "",
    ): NoteImageEntity {
        val file = File.createTempFile("bt-image-", ".bin")
        file.writeBytes(bytes)
        files += file
        return NoteImageEntity(
            id = id,
            noteId = noteId,
            path = file.absolutePath,
            mime = mime,
            caption = caption,
            addedAt = 1L,
        )
    }

    private fun imagesDir(): File =
        java.nio.file.Files.createTempDirectory("bt-images").toFile().also { dirs += it }

    private fun repo(
        store: FakeStore,
        remote: FakeRemote,
        compressor: ImageCompressor = FakeCompressor(),
        uid: String? = "uid-1",
    ) = ImageBackupRepository(
        store = store,
        remote = remote,
        compressor = compressor,
        imagesDir = imagesDir(),
        uidProvider = { uid },
        clock = { 123L },
    )

    @Test
    fun semLoginFicaPendenteESemUpload() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        val remote = FakeRemote()
        val result = repo(store, remote, uid = null).upload("img-1")
        assertEquals(ImageBackupOutcome.Deferred, result)
        assertTrue(remote.uploads.isEmpty())
        assertNull(store.backups["img-1"])
    }

    @Test
    fun jpegCompactaEEntregaWebpComMetadata() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image(caption = "Legenda")
        val remote = FakeRemote()
        val compressor = FakeCompressor(byteArrayOf(9, 9, 9, 9))
        val outcome = repo(store, remote, compressor).upload("img-1")
        assertTrue(outcome is ImageBackupOutcome.Uploaded)
        assertEquals(1, compressor.calls)
        val upload = remote.uploads.single()
        assertEquals("users/uid-1/images/note-1/img-1.webp", upload.path)
        assertEquals("image/webp", upload.contentType)
        assertEquals("Legenda", upload.metadata["caption"])
        assertEquals("note-1", upload.metadata["noteId"])
        assertEquals("uid-1", upload.metadata["uid"])
        val state = store.backups["img-1"]!!
        assertEquals("uploaded", state.state)
        assertEquals(4L, state.sizeBytes)
        assertEquals(123L, state.uploadedAt)
        assertEquals("uid-1", state.uid)
        assertNotNull(state.sha256)
    }

    @Test
    fun gifPequenoMantemAnimacaoSemRecomprimir() = runBlocking {
        val store = FakeStore()
        val gifBytes = ByteArray(100) { 7 }
        store.images["img-1"] = image(mime = "image/gif", bytes = gifBytes)
        val remote = FakeRemote()
        val compressor = FakeCompressor(byteArrayOf(1))
        repo(store, remote, compressor).upload("img-1")
        assertEquals(0, compressor.calls)
        assertEquals("image/gif", remote.uploads.single().contentType)
        assertTrue(remote.uploads.single().path.endsWith(".gif"))
        assertTrue(remote.uploads.single().bytes.contentEquals(gifBytes))
    }

    @Test
    fun falhaDoUploaderMarcaFailed() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        val remote = FakeRemote(failUpload = true)
        val outcome = repo(store, remote).upload("img-1")
        assertTrue(outcome is ImageBackupOutcome.Failed)
        assertTrue((outcome as ImageBackupOutcome.Failed).retryable)
        assertEquals("failed", store.backups["img-1"]!!.state)
        assertEquals(1, store.backups["img-1"]!!.attempts)
    }

    @Test
    fun erro404DoStorageViraMensagemAcionavel() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        val remote = FakeRemote(failUpload = true, failMessage = "Not Found.")
        val outcome = repo(store, remote).upload("img-1")
        assertTrue(outcome is ImageBackupOutcome.Failed)
        assertTrue(
            (outcome as ImageBackupOutcome.Failed).message.contains("não está ativado")
        )
        assertEquals("failed", store.backups["img-1"]!!.state)
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
            uid = "uid-1",
            updatedAt = 1,
        )
        val remote = FakeRemote()
        assertTrue(repo(store, remote).upload("img-1") is ImageBackupOutcome.Uploaded)
        assertTrue(remote.uploads.isEmpty())
    }

    @Test
    fun uploadDeOutraContaNaoEhConsideradoPronto() = runBlocking {
        val store = FakeStore()
        store.images["img-1"] = image()
        store.backups["img-1"] = ImageBackupEntity(
            imageId = "img-1",
            state = "uploaded",
            cloudPath = "users/uid-old/images/note-1/img-1.webp",
            sizeBytes = 42,
            uid = "uid-old",
            updatedAt = 1,
        )
        val remote = FakeRemote()
        repo(store, remote).upload("img-1")
        assertEquals(1, remote.uploads.size)
        assertEquals("uid-1", store.backups["img-1"]!!.uid)
    }

    @Test
    fun restoreBaixaImagemQueFalta() = runBlocking {
        val store = FakeStore()
        store.noteIds += "note-1"
        store.sectionIds += "sec-1"
        val remote = FakeRemote()
        val bytes = byteArrayOf(4, 5, 6)
        val path = "users/uid-1/images/note-1/img-nova.webp"
        remote.objects[path] = CloudImageObject(
            path = path,
            sizeBytes = bytes.size.toLong(),
            contentType = "image/webp",
            metadata = mapOf(
                "noteId" to "note-1",
                "sectionId" to "sec-1",
                "caption" to "Nuvem",
                "kind" to "photo",
                "order" to "3",
                "addedAt" to "99",
            ),
        )
        remote.downloads[path] = bytes
        val report = repo(store, remote).restore("uid-1")
        assertEquals(1, report.restored)
        assertEquals(1, report.cloudTotal)
        val image = store.images["img-nova"]!!
        assertEquals("note-1", image.noteId)
        assertEquals("sec-1", image.sectionId)
        assertEquals("Nuvem", image.caption)
        assertEquals("photo", image.kind)
        assertEquals(3, image.order)
        assertEquals(99L, image.addedAt)
        assertTrue(File(image.path).exists())
        assertTrue(File(image.path).readBytes().contentEquals(bytes))
        assertEquals("uploaded", store.backups["img-nova"]!!.state)
        assertEquals("uid-1", store.backups["img-nova"]!!.uid)
    }

    @Test
    fun restoreSemSecaoLocalMantemImagemDaNota() = runBlocking {
        val store = FakeStore()
        store.noteIds += "note-1"
        val remote = FakeRemote()
        val path = "users/uid-1/images/note-1/img-1.webp"
        remote.objects[path] = CloudImageObject(
            path = path,
            sizeBytes = 3,
            contentType = "image/webp",
            metadata = mapOf("sectionId" to "sec-sumiu"),
        )
        remote.downloads[path] = byteArrayOf(1, 2, 3)
        val report = repo(store, remote).restore("uid-1")
        assertEquals(1, report.restored)
        assertNull(store.images["img-1"]!!.sectionId)
    }

    @Test
    fun restoreNaoBaixaSemNotaLocal() = runBlocking {
        val store = FakeStore()
        val remote = FakeRemote()
        val path = "users/uid-1/images/nota-sumiu/img-1.webp"
        remote.objects[path] = CloudImageObject(path, 3, "image/webp")
        remote.downloads[path] = byteArrayOf(1, 2, 3)
        val report = repo(store, remote).restore("uid-1")
        assertEquals(0, report.restored)
        assertEquals(1, report.missingNote)
        assertNull(store.images["img-1"])
    }

    @Test
    fun restoreNaoSobrescreveLegendaLocal() = runBlocking {
        val store = FakeStore()
        store.noteIds += "note-1"
        val local = image(caption = "Local")
        store.images[local.id] = local
        val remote = FakeRemote()
        val path = "users/uid-1/images/note-1/${local.id}.webp"
        remote.objects[path] = CloudImageObject(
            path = path,
            sizeBytes = 3,
            contentType = "image/webp",
            metadata = mapOf("caption" to "Nuvem"),
        )
        val report = repo(store, remote).restore("uid-1")
        assertEquals(1, report.alreadyLocal)
        assertEquals("Local", store.images[local.id]!!.caption)
        assertTrue(remote.downloads.isEmpty())
    }

    @Test
    fun deleteCloudMantemLinhaSeFalhar() = runBlocking {
        val store = FakeStore()
        store.backups["img-1"] = ImageBackupEntity(
            imageId = "img-1",
            state = "uploaded",
            cloudPath = "users/uid-1/images/note-1/img-1.webp",
            sizeBytes = 42,
            uid = "uid-1",
            updatedAt = 1,
        )
        val remote = FakeRemote(failDelete = true)
        assertFalse(repo(store, remote).deleteCloud("img-1"))
        assertEquals("deleted", store.backups["img-1"]!!.state)
        assertNotNull(store.backups["img-1"]!!.error)
    }

    @Test
    fun deleteCloudRemoveObjetoERegistro() = runBlocking {
        val store = FakeStore()
        store.backups["img-1"] = ImageBackupEntity(
            imageId = "img-1",
            state = "uploaded",
            cloudPath = "users/uid-1/images/note-1/img-1.webp",
            sizeBytes = 42,
            uid = "uid-1",
            updatedAt = 1,
        )
        val remote = FakeRemote()
        assertTrue(repo(store, remote).deleteCloud("img-1"))
        assertEquals(
            listOf("users/uid-1/images/note-1/img-1.webp"),
            remote.deleted,
        )
        assertNull(store.backups["img-1"])
    }

    private class FakeStore : ImageBackupStore {
        val images = mutableMapOf<String, NoteImageEntity>()
        val backups = mutableMapOf<String, ImageBackupEntity>()
        val noteIds = mutableSetOf<String>()
        val sectionIds = mutableSetOf<String>()

        override suspend fun image(imageId: String): NoteImageEntity? = images[imageId]
        override suspend fun saveImage(image: NoteImageEntity) { images[image.id] = image }
        override suspend fun noteExists(noteId: String): Boolean = noteId in noteIds
        override suspend fun sectionExists(sectionId: String): Boolean = sectionId in sectionIds
        override suspend fun backup(imageId: String): ImageBackupEntity? = backups[imageId]
        override suspend fun save(backup: ImageBackupEntity) { backups[backup.imageId] = backup }
        override suspend fun deleteBackup(imageId: String) { backups.remove(imageId) }
        override suspend fun imagesForNote(noteId: String): List<NoteImageEntity> =
            images.values.filter { it.noteId == noteId }
    }

    private class FakeRemote(
        private val failUpload: Boolean = false,
        private val failMessage: String = "rede caiu",
        private val failDelete: Boolean = false,
    ) : ImageCloudRemote {
        data class Upload(
            val path: String,
            val bytes: ByteArray,
            val contentType: String,
            val metadata: Map<String, String>,
        )
        val uploads = mutableListOf<Upload>()
        val deleted = mutableListOf<String>()
        val objects = mutableMapOf<String, CloudImageObject>()
        val downloads = mutableMapOf<String, ByteArray>()

        override suspend fun upload(
            path: String,
            bytes: ByteArray,
            contentType: String,
            metadata: Map<String, String>,
        ) {
            if (failUpload) throw IllegalStateException(failMessage)
            uploads += Upload(path, bytes, contentType, metadata)
            objects[path] = CloudImageObject(path, bytes.size.toLong(), contentType, metadata)
        }

        override suspend fun list(prefix: String): List<CloudImageObject> =
            objects.values.filter { it.path.startsWith(prefix) }

        override suspend fun download(path: String): ByteArray? = downloads[path]

        override suspend fun delete(path: String) {
            if (failDelete) throw IllegalStateException("offline")
            deleted += path
            objects.remove(path)
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
