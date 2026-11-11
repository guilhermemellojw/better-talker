package com.bettertalker.app.data.images

import android.content.Context
import android.net.Uri
import com.bettertalker.app.data.db.NoteImageDao
import com.bettertalker.app.data.db.NoteImageEntity
import com.bettertalker.app.data.util.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Armazenamento privado das imagens da nota.
 *
 * Bytes em `filesDir/images/<noteId>/<id>.<ext>`; o banco guarda só o caminho.
 * Nunca usa permissão ampla de armazenamento e nunca envia a imagem ao LLM.
 */
class NoteImageStore(
    private val context: Context,
    private val dao: NoteImageDao,
) {

    fun file(path: String): File = File(path)

    suspend fun import(
        noteId: String,
        sectionId: String?,
        uri: Uri,
        caption: String = "",
        kind: String = "illustration",
        order: Int = 0,
    ): NoteImageEntity? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val ext = extensionFor(mime)
        val id = newId("img")
        val dir = File(context.filesDir, "images/$noteId").apply { mkdirs() }
        val target = File(dir, "$id.$ext")
        val ok = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            target.length() > 0
        }.getOrDefault(false)
        if (!ok) {
            target.delete()
            return@withContext null
        }
        val image = NoteImageEntity(
            id = id,
            noteId = noteId,
            sectionId = sectionId,
            path = target.absolutePath,
            mime = mime,
            caption = caption.trim(),
            kind = kind,
            order = order,
            addedAt = System.currentTimeMillis(),
        )
        dao.upsert(image)
        image
    }

    suspend fun delete(image: NoteImageEntity) = withContext(Dispatchers.IO) {
        if (image.path.isNotBlank()) {
            runCatching { File(image.path).delete() }
        }
        dao.delete(image.id)
    }

    /**
     * Melhor esforço ao excluir a nota: apaga só os BYTES privados.
     * O chamador pode estar dentro de uma transação Room; os registros caem
     * pela FK em cascade (não abrimos outra conexão a partir daqui).
     */
    suspend fun deleteFilesForNote(noteId: String) {
        val paths = dao.forNote(noteId).map { it.path }
        withContext(Dispatchers.IO) {
            paths.filter { it.isNotBlank() }.forEach {
                runCatching { File(it).delete() }
            }
            runCatching { File(context.filesDir, "images/$noteId").deleteRecursively() }
        }
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}
