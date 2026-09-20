package com.bettertalker.app.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettertalker.app.data.db.DbProvider
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.decodeBytes
import com.bettertalker.app.data.util.matchBaseSlot
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.splitRawSentences
import com.bettertalker.app.data.util.stripRtf
import com.bettertalker.app.data.util.stripXml
import java.io.ByteArrayOutputStream
import java.io.File

class IndexPublicationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_ID = "attachmentId"
        const val MAX_TEXT = 400_000
        const val MAX_SENTENCES = 2000
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val db = DbProvider.get(applicationContext)
        val att = db.attachmentDao().get(id) ?: return Result.failure()
        return try {
            val raw = when (att.kind) {
                "epub" -> readEpub(File(att.appPath))
                "docx" -> readDocx(File(att.appPath))
                "rtf" -> stripRtf(readTxt(File(att.appPath)))
                "zip" -> readZipRtfs(File(att.appPath))
                "txt" -> readTxt(File(att.appPath))
                else -> readPdf(File(att.appPath))
            }
            if (raw.isBlank()) {
                db.attachmentDao().setStatus(id, false, "failed", "Não foi possível extrair texto (arquivo vazio ou protegido).")
                return Result.success()
            }
            // separa frases no texto CRU (normalizar apaga . ! ? \n) e indexa cada uma
            val sentences = splitRawSentences(raw).take(MAX_SENTENCES)
                .mapNotNull { s ->
                    val norm = normalizeText(s)
                    if (norm.length > 5) s.replace(Regex("\\s+"), " ").trim().take(500) to norm.take(500)
                    else null
                }
            if (sentences.isEmpty()) {
                db.attachmentDao().setStatus(id, false, "failed", "Texto sem trechos aproveitáveis.")
                return Result.success()
            }
            val rows = sentences.mapIndexed { i, (text, norm) ->
                PassageEntity("$id-p$i", id, text, norm)
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

    private fun readPdf(f: File): String {
        return try {
            runCatching {
                val loader = Class.forName("com.tom_roush.pdfbox.util.PDFBoxResourceLoader")
                loader.getMethod("init", android.content.Context::class.java)
                    .invoke(null, applicationContext)
            }
            val docClass = Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument")
            val load = docClass.getMethod("load", File::class.java)
            val doc = load.invoke(null, f)
            val stripperClass = Class.forName("com.tom_roush.pdfbox.text.PDFTextStripper")
            val stripper = stripperClass.getDeclaredConstructor().newInstance()
            val getText = stripperClass.getMethod("getText", docClass)
            val end = docClass.getMethod("close")
            try {
                (getText.invoke(stripper, doc) as? String ?: "").take(MAX_TEXT)
            } finally {
                runCatching { end.invoke(doc) }
            }
        } catch (_: Exception) { "" }
    }

    private fun readEpub(f: File): String {
        return try {
            val sb = StringBuilder()
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                var count = 0
                while (entry != null && count < 40 && sb.length < MAX_TEXT) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".xhtml") || name.endsWith(".html") ||
                        name.endsWith(".xml") || name.endsWith(".opf")
                    ) {
                        val bytes = zin.readBytes()
                        if (bytes.size in 1..300_000) {
                            sb.append(String(bytes, Charsets.UTF_8)).append(' ')
                            count++
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            stripXml(sb.toString())
        } catch (_: Exception) { "" }
    }

    /** DOCX é zip: extrai word/document.xml (+ footnotes/endnotes). Sem dependência. */
    private fun readDocx(f: File): String {
        return try {
            val sb = StringBuilder()
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null && sb.length < MAX_TEXT) {
                    val name = entry.name.lowercase()
                    if (name == "word/document.xml" || name == "word/footnotes.xml" || name == "word/endnotes.xml") {
                        val bytes = zin.readBytes()
                        if (bytes.size in 1..500_000) {
                            // w:t contém o texto; <w:p>/<w:br> viram quebra
                            var xml = String(bytes, Charsets.UTF_8)
                            xml = xml.replace(Regex("<w:(p|br|tab)[^>]*/?>"), "\n")
                            sb.append(stripXml(xml)).append('\n')
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            sb.toString()
        } catch (_: Exception) { "" }
    }

    /** ZIP de RTFs: indexa TODOS os .rtf encontrados, separados por marcador. */
    private fun readZipRtfs(f: File): String {
        return try {
            val sb = StringBuilder()
            var count = 0
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null && count < 20 && sb.length < MAX_TEXT) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && name.endsWith(".rtf")) {
                        val bytes = zin.readBytes()
                        if (bytes.size in 1..300_000) {
                            val raw = decodeBytes(bytes)
                            sb.append("\n\n=== ${entry.name} ===\n")
                            sb.append(stripRtf(raw))
                            count++
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            sb.toString()
        } catch (_: Exception) { "" }
    }

    private fun readTxt(f: File): String {
        return try {
            // leitura limitada por stream (nunca o arquivo inteiro na RAM)
            val buf = ByteArrayOutputStream()
            f.inputStream().buffered().use { ins ->
                val tmp = ByteArray(64 * 1024)
                var total = 0
                while (total < MAX_TEXT * 2) {
                    val n = ins.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    total += n
                }
            }
            decodeBytes(buf.toByteArray())
        } catch (_: Exception) { "" }
    }
}
