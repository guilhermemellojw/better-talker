package com.bettertalker.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.DocExtractors
import com.bettertalker.app.data.util.matchBaseSlot
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.splitWithSections
import java.io.File

class IndexPublicationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_ID = "attachmentId"
        const val MAX_SENTENCES = 2500
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val db = DbProvider.get(applicationContext)
        val att = db.attachmentDao().get(id) ?: return Result.failure()
        return try {
            val raw = try {
                DocExtractors.extract(
                    applicationContext, File(att.appPath),
                    com.bettertalker.app.data.util.detectKind(att.fileName)
                )
            } catch (e: com.bettertalker.app.data.util.JwpubExtractor.JwpubException) {
                // mensagem específica do JWPUB (ex: esquema não suportado)
                db.attachmentDao().setStatus(id, false, "failed", e.message?.take(200))
                return Result.success()
            }.ifBlank {
                // fallback pelo kind legado gravado
                when (att.kind) {
                    "epub" -> DocExtractors.readEpub(File(att.appPath))
                    "docx" -> DocExtractors.readDocx(File(att.appPath))
                    "rtf" -> com.bettertalker.app.data.util.stripRtf(DocExtractors.readTxt(File(att.appPath)))
                    "zip" -> DocExtractors.readZipRtfs(File(att.appPath))
                    "txt" -> DocExtractors.readTxt(File(att.appPath))
                    "jwpub" -> com.bettertalker.app.data.util.JwpubExtractor.extract(applicationContext, File(att.appPath))
                    else -> DocExtractors.readPdf(applicationContext, File(att.appPath))
                }
            }
            if (raw.isBlank()) {
                db.attachmentDao().setStatus(id, false, "failed", "Não foi possível extrair texto (arquivo vazio ou protegido).")
                return Result.success()
            }
            // separa frases no texto CRU (normalizar apaga . ! ? \n) com seção
            val sentences = splitWithSections(raw).take(MAX_SENTENCES)
                .mapNotNull { (s, section) ->
                    val norm = normalizeText(s)
                    if (norm.length > 5) Triple(
                        s.replace(Regex("\\s+"), " ").trim().take(500),
                        norm.take(500),
                        section
                    ) else null
                }
            if (sentences.isEmpty()) {
                db.attachmentDao().setStatus(id, false, "failed", "Texto sem trechos aproveitáveis.")
                return Result.success()
            }
            val rows = sentences.mapIndexed { i, (text, norm, section) ->
                PassageEntity("$id-p$i", id, text, norm, section)
            }
            db.passageDao().deleteForAttachment(id)
            db.passageDao().insertAll(rows)
            db.attachmentDao().setStatus(id, true, "ready", null)
            com.bettertalker.app.data.cloud.SyncScheduler.requestSync(applicationContext)
            // auto-reconhecimento do slot base (be/th) pelo nome do arquivo
            if (att.baseSlot == null) {
                matchBaseSlot(att.fileName)?.let { db.attachmentDao().setBaseSlot(id, it) }
            }
            Result.success()
        } catch (e: Exception) {
            db.attachmentDao().setStatus(id, false, "failed", e.message?.take(200))
            Result.success()
        }
    }
}
