package com.bettertalker.app.data.images

import android.content.Context
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.ImageBackupEntity
import com.bettertalker.app.data.db.NoteImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 5.4.1 — upload + restore + fila de exclusão remota.
 *
 * O binário local é a fonte da verdade. O Storage guarda bytes normalizados e
 * customMetadata suficiente para recriar a imagem em outro aparelho (quando a
 * nota local ainda existe). Sem login, tudo fica [ImageBackupOutcome.Deferred].
 */
class ImageBackupRepository(
    private val store: ImageBackupStore,
    private val remote: ImageCloudRemote,
    private val compressor: ImageCompressor,
    private val imagesDir: File,
    private val uidProvider: () -> String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun upload(imageId: String): ImageBackupOutcome = withContext(Dispatchers.IO) {
        val image = store.image(imageId) ?: return@withContext ImageBackupOutcome.Missing
        val uid = uidProvider() ?: return@withContext ImageBackupOutcome.Deferred
        val file = File(image.path)
        if (!file.exists() || file.length() <= 0L) {
            val msg = "Arquivo local da imagem não encontrado."
            markFailed(imageId, uid, msg, attempts = null)
            return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
        }
        val existing = store.backup(imageId)
        if (existing?.state == "uploaded" &&
            existing.uid == uid &&
            existing.cloudPath?.startsWith("users/$uid/") == true
        ) {
            return@withContext ImageBackupOutcome.Uploaded(
                existing.cloudPath!!,
                existing.sizeBytes,
            )
        }
        if (!ImageBackupPolicy.isUploadable(image.mime, file.length())) {
            val msg = "Imagem acima do limite de 10 MB ou formato não suportado."
            markFailed(imageId, uid, msg, attempts = (existing?.attempts ?: 0) + 1)
            return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
        }

        val attempts = (existing?.attempts ?: 0) + 1
        val base = (existing ?: ImageBackupEntity(imageId = imageId)).copy(
            state = "uploading",
            error = null,
            attempts = attempts,
            updatedAt = clock(),
            uid = uid,
        )
        store.save(base)

        return@withContext try {
            val keep = ImageBackupPolicy.keepOriginal(image.mime, file.length())
            val compressed = if (keep) null else compressor.compress(file, image.mime)
            val bytes = if (keep) file.readBytes() else compressed?.bytes
            val ext = if (keep) {
                ImageBackupPolicy.extensionFor(image.mime, file.length())
            } else {
                compressed?.extension ?: "webp"
            }
            val contentType = if (keep) {
                ImageBackupPolicy.contentTypeFor(image.mime, file.length())
            } else {
                compressed?.mimeType ?: "image/webp"
            }
            if (bytes == null || bytes.isEmpty()) {
                val msg = "Não foi possível converter a imagem para envio."
                markFailed(imageId, uid, msg, attempts)
                return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
            }
            if (bytes.size > ImageBackupPolicy.MAX_IMAGE_BYTES) {
                val msg = "Imagem convertida ainda passa de 10 MB."
                markFailed(imageId, uid, msg, attempts)
                return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
            }
            val path = ImageBackupPolicy.remotePath(uid, image.noteId, image.id, ext)
            remote.upload(path, bytes, contentType, imageMetadata(image, uid, bytes))
            val now = clock()
            store.save(
                base.copy(
                    state = "uploaded",
                    cloudPath = path,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    uploadedAt = now,
                    error = null,
                    updatedAt = now,
                    uid = uid,
                )
            )
            ImageBackupOutcome.Uploaded(path, bytes.size.toLong())
        } catch (e: Exception) {
            val msg = friendlyError(e)
            markFailed(imageId, uid, msg, attempts)
            ImageBackupOutcome.Failed(msg, retryable = true)
        }
    }

    /**
     * Lista `users/{uid}/images`, baixa o que falta e recria NoteImageEntity.
     *
     * - nota local ausente: conta em [ImageRestoreReport.missingNote] e ignora;
     * - imagem local com arquivo presente: não sobrescreve legenda local;
     * - imagem local com arquivo ausente: baixa e preserva a legenda local;
     * - confere tamanho e sha256 quando disponíveis;
     * - idempotente.
     */
    suspend fun restore(uid: String): ImageRestoreReport = withContext(Dispatchers.IO) {
        val objects = runCatching { remote.list("users/$uid/images") }
            .getOrElse {
                return@withContext ImageRestoreReport(
                    failed = 1,
                    errors = listOf(it.message ?: "Falha ao listar o Storage."),
                )
            }
        var restored = 0
        var alreadyLocal = 0
        var missingNote = 0
        var failed = 0
        val errors = mutableListOf<String>()

        for (obj in objects) {
            val parsed = parseCloudPath(uid, obj.path)
            if (parsed == null) {
                failed++
                errors += "Caminho inválido: ${obj.path}"
                continue
            }
            if (!store.noteExists(parsed.noteId)) {
                missingNote++
                continue
            }
            val existing = store.image(parsed.imageId)
            val existingFile = existing?.let { File(it.path) }
            if (existing != null && existingFile != null &&
                existingFile.exists() && existingFile.length() > 0L
            ) {
                alreadyLocal++
                saveUploadedRow(uid, obj, parsed.imageId, existingFile.length())
                continue
            }

            val bytes = runCatching { remote.download(obj.path) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                failed++
                errors += "Não baixou ${obj.path}"
                continue
            }
            if (obj.sizeBytes > 0L && bytes.size.toLong() != obj.sizeBytes) {
                failed++
                errors += "Tamanho divergente em ${obj.path}"
                continue
            }
            val expectedSha = obj.metadata["sha256"]
            if (!expectedSha.isNullOrBlank() && !expectedSha.equals(sha256(bytes), true)) {
                failed++
                errors += "Hash divergente em ${obj.path}"
                continue
            }
            val ext = parsed.extension.ifBlank { "webp" }
            val dir = File(imagesDir, parsed.noteId).apply { mkdirs() }
            val target = File(dir, "${parsed.imageId}.$ext")
            val written = runCatching { target.writeBytes(bytes) }.isSuccess
            if (!written) {
                failed++
                errors += "Não gravou ${target.name}"
                continue
            }
            val mime = obj.contentType?.takeIf { it.startsWith("image/") }
                ?: obj.metadata["mime"]
                ?: "image/webp"
            val image = existing?.copy(
                path = target.absolutePath,
                mime = mime,
            ) ?: NoteImageEntity(
                id = parsed.imageId,
                noteId = parsed.noteId,
                sectionId = obj.metadata["sectionId"]
                    ?.takeIf { it.isNotBlank() && store.sectionExists(it) },
                path = target.absolutePath,
                mime = mime,
                caption = obj.metadata["caption"].orEmpty(),
                kind = obj.metadata["kind"]?.takeIf { it.isNotBlank() } ?: "illustration",
                order = obj.metadata["order"]?.toIntOrNull() ?: 0,
                addedAt = obj.metadata["addedAt"]?.toLongOrNull() ?: clock(),
            )
            store.saveImage(image)
            saveUploadedRow(uid, obj, image.id, bytes.size.toLong())
            restored++
        }
        ImageRestoreReport(
            cloudTotal = objects.size,
            restored = restored,
            alreadyLocal = alreadyLocal,
            missingNote = missingNote,
            failed = failed,
            errors = errors.take(5),
        )
    }

    /**
     * Apaga a cópia remota. Em falha, mantém a linha `deleted` para retry/GC;
     * só remove a linha local depois do sucesso (ou se não há caminho).
     */
    suspend fun deleteCloud(imageId: String): Boolean = withContext(Dispatchers.IO) {
        val row = store.backup(imageId) ?: return@withContext true
        val path = row.cloudPath
        if (path.isNullOrBlank()) {
            store.deleteBackup(imageId)
            return@withContext true
        }
        val ok = runCatching { remote.delete(path) }.isSuccess
        if (ok) {
            store.deleteBackup(imageId)
        } else {
            store.save(
                row.copy(
                    state = "deleted",
                    error = "Falha ao apagar no Storage; tentaremos de novo.",
                    updatedAt = clock(),
                )
            )
        }
        ok
    }

    /** Retorna os imageIds cuja remoção remota falhou (para enfileirar). */
    suspend fun deleteCloudForNote(noteId: String): List<String> = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        store.imagesForNote(noteId).forEach { image ->
            if (!deleteCloud(image.id)) failed += image.id
        }
        failed
    }

    private fun imageMetadata(
        image: NoteImageEntity,
        uid: String,
        bytes: ByteArray,
    ): Map<String, String> = mapOf(
        "uid" to uid,
        "noteId" to image.noteId,
        "sectionId" to (image.sectionId ?: ""),
        "caption" to image.caption,
        "kind" to image.kind,
        "order" to image.order.toString(),
        "mime" to image.mime,
        "addedAt" to image.addedAt.toString(),
        "sha256" to sha256(bytes),
    )

    private suspend fun saveUploadedRow(
        uid: String,
        obj: CloudImageObject,
        imageId: String,
        sizeBytes: Long,
    ) {
        val now = clock()
        store.save(
            ImageBackupEntity(
                imageId = imageId,
                state = "uploaded",
                cloudPath = obj.path,
                sizeBytes = sizeBytes,
                sha256 = obj.metadata["sha256"],
                uploadedAt = now,
                error = null,
                attempts = 0,
                updatedAt = now,
                uid = uid,
            )
        )
    }

    private suspend fun markFailed(
        imageId: String,
        uid: String,
        message: String,
        attempts: Int?,
    ) {
        val current = store.backup(imageId)
        store.save(
            (current ?: ImageBackupEntity(imageId = imageId)).copy(
                state = "failed",
                error = message.take(160),
                attempts = attempts ?: current?.attempts ?: 1,
                updatedAt = clock(),
                uid = uid,
            )
        )
    }

    private fun friendlyError(e: Exception): String {
        val raw = e.message?.take(160) ?: "Falha no upload da imagem."
        return if (raw.contains("Not Found", ignoreCase = true) ||
            raw.contains("404") ||
            raw.contains("does not exist", ignoreCase = true)
        ) {
            "Firebase Storage não está ativado no projeto."
        } else {
            raw
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class ParsedCloudPath(
        val noteId: String,
        val imageId: String,
        val extension: String,
    )

    /** users/{uid}/images/{noteId}/{imageId}.{ext} */
    private fun parseCloudPath(uid: String, path: String): ParsedCloudPath? {
        val parts = path.trimStart('/').split('/')
        if (parts.size != 5) return null
        if (parts[0] != "users" || parts[1] != uid || parts[2] != "images") return null
        val noteId = parts[3].takeIf { it.isNotBlank() } ?: return null
        val fileName = parts[4]
        val imageId = fileName.substringBeforeLast('.', "")
            .takeIf { it.isNotBlank() } ?: return null
        val ext = fileName.substringAfterLast('.', "")
        return ParsedCloudPath(noteId, imageId, ext)
    }

    companion object {
        fun create(context: Context, db: AppDatabase): ImageBackupRepository =
            ImageBackupRepository(
                store = RoomImageBackupStore(db),
                remote = FirebaseImageUploader(),
                compressor = AndroidImageCompressor(),
                imagesDir = File(context.filesDir, "images"),
                uidProvider = {
                    com.bettertalker.app.data.cloud.AuthRepository(context)
                        .currentUser()?.uid
                },
            )
    }
}
