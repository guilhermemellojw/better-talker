package com.bettertalker.app.data.images

import android.content.Context
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.ImageBackupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 5.4 — orquestra compactação + upload + estado local.
 *
 * Idempotente: um `uploaded` com caminho não sobe de novo. O binário local é
 * a fonte da verdade; o Storage é cópia de segurança. Sem login, devolve
 * [ImageBackupOutcome.Deferred] e nada é apagado.
 */
class ImageBackupRepository(
    private val store: ImageBackupStore,
    private val uploader: ImageUploader,
    private val compressor: ImageCompressor,
    private val uidProvider: () -> String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun upload(imageId: String): ImageBackupOutcome = withContext(Dispatchers.IO) {
        val image = store.image(imageId) ?: return@withContext ImageBackupOutcome.Missing
        val uid = uidProvider() ?: return@withContext ImageBackupOutcome.Deferred
        val file = File(image.path)
        if (!file.exists() || file.length() <= 0L) {
            val msg = "Arquivo local da imagem não encontrado."
            markFailed(imageId, msg, attempts = null)
            return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
        }
        val existing = store.backup(imageId)
        if (existing?.state == "uploaded" && !existing.cloudPath.isNullOrBlank()) {
            return@withContext ImageBackupOutcome.Uploaded(
                existing.cloudPath,
                existing.sizeBytes,
            )
        }
        if (!ImageBackupPolicy.isUploadable(image.mime, file.length())) {
            val msg = "Imagem acima do limite de 10 MB ou formato não suportado."
            markFailed(imageId, msg, attempts = (existing?.attempts ?: 0) + 1)
            return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
        }

        val attempts = (existing?.attempts ?: 0) + 1
        val base = (existing ?: ImageBackupEntity(imageId = imageId)).copy(
            state = "uploading",
            error = null,
            attempts = attempts,
            updatedAt = clock(),
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
                markFailed(imageId, msg, attempts)
                return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
            }
            if (bytes.size > ImageBackupPolicy.MAX_IMAGE_BYTES) {
                val msg = "Imagem convertida ainda passa de 10 MB."
                markFailed(imageId, msg, attempts)
                return@withContext ImageBackupOutcome.Failed(msg, retryable = false)
            }
            val path = ImageBackupPolicy.remotePath(uid, image.noteId, image.id, ext)
            uploader.upload(path, bytes, contentType)
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
                )
            )
            ImageBackupOutcome.Uploaded(path, bytes.size.toLong())
        } catch (e: Exception) {
            val msg = friendlyError(e)
            markFailed(imageId, msg, attempts)
            ImageBackupOutcome.Failed(msg, retryable = true)
        }
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

    /** Chamado ao remover a imagem: apaga a cópia remota e o estado local. */
    suspend fun deleteCloud(imageId: String) = withContext(Dispatchers.IO) {
        val row = store.backup(imageId) ?: return@withContext
        row.cloudPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { uploader.delete(path) }
        }
        store.deleteBackup(imageId)
    }

    /** Chamado antes da cascata da nota; preserva o original local até o fim. */
    suspend fun deleteCloudForNote(noteId: String) = withContext(Dispatchers.IO) {
        store.imagesForNote(noteId).forEach { deleteCloud(it.id) }
    }

    private suspend fun markFailed(
        imageId: String,
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
            )
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun create(context: Context, db: AppDatabase): ImageBackupRepository =
            ImageBackupRepository(
                store = RoomImageBackupStore(db),
                uploader = FirebaseImageUploader(),
                compressor = AndroidImageCompressor(),
                uidProvider = {
                    com.bettertalker.app.data.cloud.AuthRepository(context)
                        .currentUser()?.uid
                },
            )
    }
}
