package com.bettertalker.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.storage.PublicationStorage
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.detectSymbol
import com.bettertalker.app.data.util.matchBaseSlot
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.work.IndexPublicationWorker
import com.bettertalker.app.data.work.RegisterDownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImportException(val reason: Reason, msg: String) : Exception(msg) {
    enum class Reason { UNSUPPORTED, TOO_BIG, MISMATCH, IO }
}

/** Símbolo canônico para comparar publicação de um arquivo/anexo. */
internal fun canonicalPublicationSymbol(raw: String?): String? {
    val s = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    if (s == "s38" || s == "s-38") return "s38"
    PubCatalog.unifiedOf(s)?.let { return it }
    PubCatalog.resolveSymbol(s)?.let { return it }
    // Revista com edição no nome (w19.03, wp19.03, g 6/07, gn 1/24).
    if (Regex("""^(wp?|gn?)(?=[\d\s._-])""").containsMatchIn(s)) return s
    return null
}

/** Publicação inferida de um anexo (baseSlot → symbol → nome do arquivo). */
internal fun AttachmentEntity.publicationKeyOrNull(): String? {
    baseSlot?.takeIf { it.isNotBlank() }?.let {
        canonicalPublicationSymbol(it)?.let { key -> return key }
    }
    symbol?.takeIf { it.isNotBlank() }?.let {
        canonicalPublicationSymbol(it)?.let { key -> return key }
    }
    matchBaseSlot(fileName)?.let { return it }
    val detected = detectSymbol(fileName)
    return canonicalPublicationSymbol(detected)
}

/** Publicação inferida do arquivo escolhido para reparar o anexo. */
internal fun filePublicationKeyOrNull(fileName: String): String? {
    matchBaseSlot(fileName)?.let { return it }
    return canonicalPublicationSymbol(detectSymbol(fileName))
}

/**
 * A9: null = mesma publicação (ou impossível provar que é outra).
 * Pair(esperado, recebido) = arquivo claramente de outra publicação.
 */
internal fun repairPublicationMismatch(
    current: AttachmentEntity,
    candidateFileName: String,
): Pair<String, String>? {
    val expected = current.publicationKeyOrNull() ?: return null
    val actual = filePublicationKeyOrNull(candidateFileName) ?: return null
    return if (expected == actual) null else expected to actual
}

class LibraryRepository(private val ctx: Context, private val db: AppDatabase) {
    companion object {
        // Tetos vivem em [com.bettertalker.app.data.util.ImportLimits].
    }

    fun observe(): Flow<List<AttachmentEntity>> = db.attachmentDao().observe()
    suspend fun all() = db.attachmentDao().all()

    private fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun findByHash(hash: String): AttachmentEntity? =
        db.attachmentDao().all().firstOrNull { it.sha256 == hash }

    /**
     * Dedupe não pode perder o vínculo pedido agora: se o anexo existente
     * ainda não pertence a nenhuma nota, adota a nota do import atual.
     */
    private suspend fun linkIfUnlinked(existing: AttachmentEntity, noteId: String?) {
        if (noteId != null && existing.noteId == null) {
            db.attachmentDao().setNote(existing.id, noteId)
            SyncScheduler.requestSync(ctx)
        }
    }

    /** P1: publicação já importada com este nome (evita baixar de novo). */
    suspend fun findExistingByName(fileName: String): AttachmentEntity? =
        db.attachmentDao().all().firstOrNull {
            it.fileName == fileName && it.appPath.isNotBlank() && File(it.appPath).isFile
        }

    suspend fun baseReady(slot: String) = db.attachmentDao().baseReady(slot)

    private fun displayNameOf(uri: Uri): String = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "arquivo-${System.currentTimeMillis()}.pdf"
    } catch (e: Exception) {
        throw ImportException(ImportException.Reason.IO, "Não foi possível ler o arquivo.")
    }

    /** Copia a URI para o cache respeitando o limite; apaga o tmp em falha. */
    private suspend fun copyUriToTemp(uri: Uri, name: String): File {
        val tmp = File(ctx.cacheDir, "imp-${java.util.UUID.randomUUID()}-$name")
        try {
            // Fase 0: a cópia pode chegar a 400 MB — nunca na Main.
            withContext(Dispatchers.IO) {
                val ins = ctx.contentResolver.openInputStream(uri)
                    ?: throw ImportException(ImportException.Reason.IO, "Não foi possível abrir o arquivo.")
                ins.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > com.bettertalker.app.data.util.ImportLimits.LIBRARY_BYTES) {
                                throw ImportException(ImportException.Reason.TOO_BIG, "Arquivo muito grande (limite 400 MB).")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            return tmp
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.delete()
            throw e
        } catch (e: ImportException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ImportException(ImportException.Reason.IO, "Falha ao copiar o arquivo.")
        }
    }

    /** Importa via SAF preservando nome/extensão reais. Uso pessoal BYOD. */
    @Throws(ImportException::class)
    suspend fun importUri(uri: Uri, noteId: String? = null): String {
        val name = displayNameOf(uri)
        val kind = detectKind(name)
        if (kind == DocKind.UNSUPPORTED) {
            throw ImportException(ImportException.Reason.UNSUPPORTED, "Formato não suportado: $name. Use PDF, EPUB, DOCX, RTF, ZIP, TXT ou JWPUB.")
        }
        val tmp = copyUriToTemp(uri, name)
        try {
            return importFile(tmp, name, noteId)
                ?: throw ImportException(ImportException.Reason.UNSUPPORTED, "Formato não suportado.")
        } finally {
            // Fase 0: o tmp de cache não pode sobreviver ao import (sucesso ou falha).
            tmp.delete()
        }
    }

    /**
     * M4: restaura um anexo `missing` (metadados vieram de outro aparelho,
     * binário não) com um arquivo escolhido no próprio aparelho. Mantém o id
     * sincronizado e o vínculo com a nota; a indexação roda de novo.
     */
    @Throws(ImportException::class)
    suspend fun repairMissing(id: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val cur = db.attachmentDao().get(id)
            ?: throw ImportException(ImportException.Reason.IO, "Anexo não encontrado.")
        val name = displayNameOf(uri)
        val kind = detectKind(name)
        if (kind == DocKind.UNSUPPORTED) {
            throw ImportException(ImportException.Reason.UNSUPPORTED, "Formato não suportado: $name. Use PDF, EPUB, DOCX, RTF, ZIP, TXT ou JWPUB.")
        }
        repairPublicationMismatch(cur, name)?.let { (expected, actual) ->
            throw ImportException(
                ImportException.Reason.MISMATCH,
                "Este arquivo é de outra publicação (esperado: $expected; recebido: $actual). " +
                    "Escolha o arquivo correspondente ao anexo.",
            )
        }
        val tmp = copyUriToTemp(uri, name)
        var dst: File? = null
        try {
            com.bettertalker.app.data.util.ImportValidation.invalidReason(tmp, kind)?.let {
                throw ImportException(ImportException.Reason.UNSUPPORTED, it)
            }
            val hash = sha256Of(tmp)
            val target = PublicationStorage.internalFile(ctx, id, name)
            dst = target
            tmp.copyTo(target, overwrite = true)
            db.attachmentDao().update(
                cur.copy(
                    fileName = name,
                    kind = kind.ext,
                    sizeBytes = target.length(),
                    appPath = target.absolutePath,
                    indexed = false,
                    status = "indexing",
                    error = null,
                    baseSlot = cur.baseSlot ?: matchBaseSlot(name),
                    symbol = cur.symbol ?: matchBaseSlot(name),
                    sha256 = hash,
                )
            )
            enqueueIndex(id)
            SyncScheduler.requestSync(ctx)
            id
        } catch (e: kotlinx.coroutines.CancellationException) {
            dst?.delete()
            throw e
        } catch (e: ImportException) {
            dst?.delete()
            throw e
        } catch (e: Exception) {
            dst?.delete()
            throw ImportException(ImportException.Reason.IO, "Falha ao restaurar o arquivo.")
        } finally {
            tmp.delete()
        }
    }

    /** Importa arquivo já baixado (download/SAF) para pasta privada do app. */
    @Throws(ImportException::class)
    suspend fun importFile(src: File, displayName: String = src.name, noteId: String? = null): String =
        withContext(Dispatchers.IO) {
            val kind = detectKind(displayName, src)
            if (kind == DocKind.UNSUPPORTED) {
                throw ImportException(ImportException.Reason.UNSUPPORTED, explicitMessage(displayName))
            }
            com.bettertalker.app.data.util.ImportValidation.invalidReason(src, kind)?.let {
                throw ImportException(ImportException.Reason.UNSUPPORTED, it)
            }
            if (src.length() > com.bettertalker.app.data.util.ImportLimits.LIBRARY_BYTES) {
                throw ImportException(ImportException.Reason.TOO_BIG, "Arquivo muito grande (limite 400 MB).")
            }
            var dst: File? = null
            try {
                val hash = sha256Of(src)
                // P1: mesmo arquivo não vira segundo anexo. Se o registro
                // antigo perdeu o binário, reusa o caminho e reindexa.
                val existing = findByHash(hash)
                if (existing != null) {
                    val existingFile = File(existing.appPath)
                    if (existingFile.isFile) {
                        linkIfUnlinked(existing, noteId)
                        return@withContext existing.id
                    }
                    val target = PublicationStorage.internalFile(ctx, existing.id, existing.fileName)
                    dst = target
                    src.copyTo(target, overwrite = true)
                    db.attachmentDao().upsert(
                        existing.copy(
                            appPath = target.absolutePath,
                            sizeBytes = target.length(),
                            indexed = false,
                            status = "indexing",
                            error = null,
                            sha256 = hash,
                            noteId = existing.noteId ?: noteId,
                        )
                    )
                    enqueueIndex(existing.id)
                    SyncScheduler.requestSync(ctx)
                    return@withContext existing.id
                }
                val id = newId("att")
                val target = PublicationStorage.internalFile(ctx, id, displayName)
                dst = target
                src.copyTo(target, overwrite = true)
                val slot = matchBaseSlot(displayName)
                db.attachmentDao().upsert(
                    AttachmentEntity(
                        id, noteId, displayName, kind.ext, target.length(), target.absolutePath,
                        false, System.currentTimeMillis(), slot, "indexing", null,
                        sha256 = hash,
                    )
                )
                enqueueIndex(id)
                SyncScheduler.requestSync(ctx)
                id
            } catch (e: kotlinx.coroutines.CancellationException) {
                dst?.delete()
                throw e
            } catch (e: Exception) {
                // Não deixa arquivo parcial na pasta de publicações.
                dst?.delete()
                throw ImportException(ImportException.Reason.IO, "Falha ao importar o arquivo.")
            }
        }

    private fun explicitMessage(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jwpub" -> "Este JWPUB não pôde ser aberto (arquivo corrompido ou formato não reconhecido). " +
                "Tente reindexar; se persistir, baixe o EPUB."
            "brl", "bes" -> "Formato $ext ainda não suportado — baixe EPUB ou PDF no site."
            "mp3", "aac", "m4a" -> "Áudio não é indexado — baixe o texto (PDF/EPUB) no site."
            else -> "Formato não suportado: $fileName. Use PDF, EPUB, DOCX, RTF, ZIP, TXT ou JWPUB."
        }
    }

    /** Entrada provisória "Baixando…" criada no momento do enqueue do DownloadManager. */
    suspend fun insertPlaceholder(
        fileName: String,
        noteId: String? = null,
        downloadId: Long = -1L,
        sourceUrl: String? = null
    ): String? {
        if (detectKind(fileName) == DocKind.UNSUPPORTED) return null
        val id = newId("att")
        db.attachmentDao().upsert(
            AttachmentEntity(
                id, noteId, fileName, detectKind(fileName).ext, 0L, "",
                false, System.currentTimeMillis(), matchBaseSlot(fileName),
                "downloading", null, downloadId, sourceUrl
            )
        )
        return id
    }

    /** Vincula dmId/URL de origem a um placeholder existente. */
    suspend fun bindDownload(id: String, downloadId: Long, sourceUrl: String?) {
        val cur = db.attachmentDao().get(id) ?: return
        db.attachmentDao().update(cur.copy(downloadId = downloadId, sourceUrl = sourceUrl))
    }

    /** Conclui o placeholder com o arquivo copiado e agenda a indexação. */
    suspend fun completePlaceholder(id: String, src: File, displayName: String, noteId: String? = null) {
        val cur = db.attachmentDao().get(id) ?: run {
            importFile(src, displayName, noteId); return
        }
        val kind = detectKind(displayName, src)
        if (kind == DocKind.UNSUPPORTED) {
            db.attachmentDao().update(cur.copy(status = "failed", error = explicitMessage(displayName)))
            return
        }
        com.bettertalker.app.data.util.ImportValidation.invalidReason(src, kind)?.let { reason ->
            db.attachmentDao().update(cur.copy(status = "failed", error = reason))
            return
        }
        try {
            val hash = sha256Of(src)
            // P1: download repetido da mesma edição reusa o anexo existente.
            val existing = findByHash(hash)?.takeIf { it.id != id }
            if (existing != null && File(existing.appPath).isFile) {
                linkIfUnlinked(existing, cur.noteId ?: noteId)
                db.attachmentDao().delete(id)
                return
            }
            val dst = PublicationStorage.internalFile(ctx, id, displayName)
            src.copyTo(dst, overwrite = true)
            db.attachmentDao().update(
                cur.copy(
                    fileName = displayName, kind = kind.ext, sizeBytes = dst.length(),
                    appPath = dst.absolutePath, indexed = false, status = "indexing",
                    error = null, baseSlot = cur.baseSlot ?: matchBaseSlot(displayName),
                    noteId = noteId ?: cur.noteId, sha256 = hash,
                )
            )
            enqueueIndex(id)
            SyncScheduler.requestSync(ctx)
        } catch (_: Exception) {
            db.attachmentDao().update(cur.copy(status = "failed", error = "Falha ao importar o download."))
        }
    }

    suspend fun failPlaceholder(id: String, msg: String) {
        val cur = db.attachmentDao().get(id) ?: return
        db.attachmentDao().update(cur.copy(status = "failed", error = msg))
    }

    suspend fun delete(id: String) {
        val a = db.attachmentDao().get(id)
        if (a != null) {
            runCatching { File(a.appPath).delete() }
            db.passageDao().deleteForAttachment(id)
            // Fase 19-B.3: outline estrutural órfão não permanece.
            S34OutlineRepository(db.s34Dao()).deleteBySource(id)
            db.attachmentDao().delete(id)
            db.tombstoneDao().put(TombstoneEntity(id, "attachment", System.currentTimeMillis()))
            SyncScheduler.requestSync(ctx)
        }
    }

    suspend fun reindex(id: String) {
        db.attachmentDao().setStatus(id, false, "indexing", null)
        enqueueIndex(id)
        SyncScheduler.requestSync(ctx)
    }

    /**
     * Tenta de novo o REGISTRO de um download (usa o downloadId guardado;
     * o DownloadManager mantém o arquivo concluído). Para entradas que
     * nunca foram importadas (appPath vazio).
     */
    suspend fun retryRegister(id: String): Boolean {
        val cur = db.attachmentDao().get(id) ?: return false
        if (cur.downloadId < 0) return false
        db.attachmentDao().update(cur.copy(status = "downloading", error = null))
        enqueueRegisterDownload(cur.downloadId, cur.fileName, id, cur.noteId)
        return true
    }

    /**
     * Baixa de novo via URL de origem guardada (arquivo direto).
     * Retorna false se não há URL de arquivo (caso de página -> abrir WebView).
     */
    suspend fun redownloadDirect(id: String): Boolean {
        val cur = db.attachmentDao().get(id) ?: return false
        val url = cur.sourceUrl ?: return false
        if (com.bettertalker.app.ui.jw.DownloadHelper.isPageUrl(url)) return false
        val dmId = com.bettertalker.app.ui.jw.DownloadHelper.enqueue(ctx, url, cur.fileName)
            ?: return false
        db.attachmentDao().update(
            cur.copy(status = "downloading", error = null, downloadId = dmId)
        )
        enqueueRegisterDownload(dmId, cur.fileName, id, cur.noteId)
        return true
    }

    /** Reindexa todo o acervo uma vez (migração de formato de índice). */
    suspend fun reindexAll() {
        for (a in db.attachmentDao().all()) {
            if (a.status != "downloading") reindex(a.id)
        }
    }

    suspend fun linkToNote(id: String, noteId: String?) {
        db.attachmentDao().setNote(id, noteId)
        SyncScheduler.requestSync(ctx)
    }

    suspend fun markBaseSlot(id: String, slot: String?) {
        db.attachmentDao().setBaseSlot(id, slot)
        SyncScheduler.requestSync(ctx)
    }

    fun enqueueRegisterDownload(dmId: Long, fileName: String, placeholderId: String, noteId: String? = null) {
        val req = OneTimeWorkRequestBuilder<RegisterDownloadWorker>()
            .setInputData(
                workDataOf(
                    RegisterDownloadWorker.KEY_DM_ID to dmId,
                    RegisterDownloadWorker.KEY_NAME to fileName,
                    RegisterDownloadWorker.KEY_PLACEHOLDER to placeholderId,
                    RegisterDownloadWorker.KEY_NOTE to noteId
                )
            ).build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    private fun enqueueIndex(attachmentId: String) {
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<IndexPublicationWorker>()
                .setInputData(workDataOf(IndexPublicationWorker.KEY_ID to attachmentId)).build()
        )
    }
}
