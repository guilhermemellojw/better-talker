package com.bettertalker.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.TrainingClassifier
import com.bettertalker.app.data.s34.NwtEpubParser
import com.bettertalker.app.data.util.DocExtractors
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.detectSymbol
import com.bettertalker.app.data.util.matchBaseSlot
import com.bettertalker.app.data.util.normalizeText
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
            // F19-B.6: S-34 importado vira estrutura persistida automaticamente.
            // Documento comum não muda de fluxo; S-34 insuficiente não vira
            // estrutura falsa (hook devolve o estado, nunca lança).
            com.bettertalker.app.data.s34.S34ImportHook.onExtracted(db.s34Dao(), id, raw)
            // Fase 8: trilho via slot (legado ou recém-detectado); categoria
            // só para training (demais trilhos: null, sem inferência).
            val slot = att.baseSlot ?: matchBaseSlot(att.fileName)
            val sourceType = SourceType.fromBaseSlot(slot)
            // buildPassages preserva filtro/caps/ord do fluxo anterior e popula
            // ref no formato web ("symbol section §n").
            val symbol = detectSymbol(att.fileName)
            // TNM em EPUB: versículo-a-versículo com ref canônico (2.6b).
            // Ordem preservada: DocExtractors.extract já rodou acima (S34Hook).
            var passages = if (symbol == "nwt" && att.kind == "epub") {
                NwtEpubParser.extractVerses(File(att.appPath)).mapIndexed { i, v ->
                    PassageEntity(
                        id = "$id-p$i",
                        attachmentId = id,
                        text = v.text,
                        normalized = normalizeText(v.text),
                        section = v.section,
                        ref = v.canonicalRef,
                        ord = i,
                        trainingCategory = null,
                    )
                }
            } else {
                buildPassages(id, symbol, raw, null,
                    maxSentences = MAX_SENTENCES)
            }
            if (sourceType == SourceType.TRAINING) {
                // Categoria varia por frase (seção/texto) — refino preservando o comportamento anterior.
                passages = passages.map {
                    it.copy(trainingCategory = TrainingClassifier.classify(it.section, att.fileName, it.text).serial)
                }
            }
            if (passages.isEmpty()) {
                db.attachmentDao().setStatus(id, false, "failed", "Texto sem trechos aproveitáveis.")
                return Result.success()
            }
            db.passageDao().deleteForAttachment(id)
            db.passageDao().insertAll(passages)
            // Fase 8: persiste trilho/símbolo do anexo (símbolo = slot quando conhecido).
            val finalSlot = att.baseSlot ?: matchBaseSlot(att.fileName)
            db.attachmentDao().setSourceMeta(id, SourceType.fromBaseSlot(finalSlot).serial, finalSlot)
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
