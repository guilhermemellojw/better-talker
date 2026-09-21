package com.bettertalker.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bettertalker.app.data.cloud.SyncScheduler
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.db.TombstoneEntity
import com.bettertalker.app.data.util.DocExtractors
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.ParsedOutline
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.plainFromMarkdown
import kotlinx.coroutines.flow.Flow
import java.io.File

class OutlineRepository(private val ctx: Context, private val db: AppDatabase) {

    data class OutlinePreview(val fileName: String, val parsed: ParsedOutline, val refsJson: String)

    fun observe(noteId: String): Flow<List<OutlineEntity>> = db.outlineDao().observeForNote(noteId)
    suspend fun get(noteId: String) = db.outlineDao().getForNote(noteId)

    /** Importa DOCX/PDF/JWPUB via SAF e devolve o parse + refs para prévia (sem vincular). */
    @Throws(ImportException::class)
    suspend fun previewFile(uri: Uri): OutlinePreview {
        val name = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "esboco"
        } catch (_: Exception) {
            throw ImportException(ImportException.Reason.IO, "Não foi possível ler o arquivo.")
        }
        val kind = detectKind(name)
        if (kind != DocKind.DOCX && kind != DocKind.PDF && kind != DocKind.JWPUB) {
            throw ImportException(
                ImportException.Reason.UNSUPPORTED,
                "Esboço precisa ser DOCX, PDF ou JWPUB (ou cole o texto)."
            )
        }
        val tmp = File(ctx.cacheDir, "out-${System.currentTimeMillis()}-$name")
        try {
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
                        if (total > LibraryRepository.MAX_IMPORT_BYTES) {
                            tmp.delete()
                            throw ImportException(ImportException.Reason.TOO_BIG, "Arquivo muito grande (limite 150 MB).")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (_: Exception) {
            tmp.delete()
            throw ImportException(ImportException.Reason.IO, "Falha ao copiar o arquivo.")
        }
        val text = try {
            DocExtractors.extract(ctx, tmp, kind)
        } catch (e: com.bettertalker.app.data.util.JwpubExtractor.JwpubException) {
            throw ImportException(ImportException.Reason.IO, e.message ?: "Falha ao ler o .jwpub.")
        } finally {
            tmp.delete()
        }
        if (text.isBlank()) {
            throw ImportException(ImportException.Reason.IO, "Não extraí texto do arquivo (vazio ou protegido).")
        }
        val parsed = OutlineParser.parse(text, name)
        if (parsed.sections.size < 2) {
            throw ImportException(
                ImportException.Reason.IO,
                "Não reconheci seções no formato (N min). Confira o arquivo ou cole o texto."
            )
        }
        val refsJson = com.bettertalker.app.data.util.RefDetector.detectedToJson(
            com.bettertalker.app.data.util.RefDetector.detect(text)
        )
        return OutlinePreview(name, parsed, refsJson)
    }

    /** Vincula o esboço à nota (um por nota; substitui) + pré-preenche o esqueleto integral. */
    suspend fun link(
        noteId: String,
        fileName: String,
        title: String,
        totalMinutes: Int?,
        sections: List<OutlineSection>,
        refsJson: String = "[]",
        preamble: String = ""
    ) {
        val now = System.currentTimeMillis()
        db.outlineDao().upsert(
            OutlineEntity(
                id = "out-$noteId", noteId = noteId, fileName = fileName,
                title = title.ifBlank { "Esboço" }, totalMinutes = totalMinutes,
                sectionsJson = OutlineParser.toJson(sections, preamble),
                refsJson = refsJson,
                createdAt = db.outlineDao().getForNote(noteId)?.createdAt ?: now,
                updatedAt = now
            )
        )
        prefillSkeleton(noteId, sections, preamble)
        SyncScheduler.requestSync(ctx)
    }

    /** Esqueleto integral: preâmbulo + ## Seção [(N min)] com o corpo original. */
    suspend fun prefillSkeleton(noteId: String, sections: List<OutlineSection>, preamble: String = "") {
        val note = db.noteDao().get(noteId) ?: return
        val parts = mutableListOf<String>()
        if (preamble.isNotBlank()) parts += preamble
        sections.forEach {
            val head = "## " + it.title + (if (it.minutes != null) " (${it.minutes} min)" else "")
            parts += if (it.body.isNotBlank()) "$head\n\n${it.body}" else head
        }
        val skeleton = parts.joinToString("\n\n")
        val md = if (note.mdText.isBlank()) skeleton else (note.mdText.trimEnd() + "\n\n" + skeleton).trim()
        db.noteDao().upsert(
            note.copy(
                mdText = md, plainText = plainFromMarkdown(md),
                updatedAt = System.currentTimeMillis()
            )
        )
        SyncScheduler.requestSync(ctx)
    }

    suspend fun unlink(noteId: String) {
        val cur = db.outlineDao().getForNote(noteId) ?: return
        db.outlineDao().deleteForNote(noteId)
        db.tombstoneDao().put(TombstoneEntity(cur.id, "outline", System.currentTimeMillis()))
        SyncScheduler.requestSync(ctx)
    }

    suspend fun sections(noteId: String): List<OutlineSection> {
        val o = db.outlineDao().getForNote(noteId) ?: return emptyList()
        return OutlineParser.fromJson(o.sectionsJson)
    }
}
