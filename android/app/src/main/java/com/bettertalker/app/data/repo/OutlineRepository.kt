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

    /** Vincula o esboço à nota (um por nota; substitui). O esqueleto vai
     * pela fila de inserção do editor (preserva estilos); aqui só persiste. */
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
        // O esqueleto vai pela fila de inserção do editor (preserva estilos);
        // aqui só persiste o esboço. Ver skeletonMarkdown().
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

    /**
     * Recalcula o esboço a partir do markdown atual da nota (edição livre
     * no editor). Preserva refs, preâmbulo e total. Novas ## não entram
     * sozinhas (vão em SyncResult.added p/ confirmação).
     */
    suspend fun refreshFromMarkdown(
        noteId: String,
        mdText: String,
        insertedTitles: Set<String> = emptySet()
    ): com.bettertalker.app.data.util.SyncResult {
        val o = db.outlineDao().getForNote(noteId)
            ?: return com.bettertalker.app.data.util.SyncResult(emptyList(), emptyList(), emptyList())
        val (preamble, linked) = OutlineParser.parseEnvelope(
            o.sectionsJson.takeIf { it.isNotBlank() } ?: "[]"
        )
        val (updated, result) = com.bettertalker.app.data.util.syncSections(linked, mdText, insertedTitles)
        db.outlineDao().upsert(
            o.copy(
                sectionsJson = OutlineParser.toJson(updated, preamble),
                updatedAt = System.currentTimeMillis()
            )
        )
        return result
    }

    /** Adiciona tópicos confirmados ao fim do esboço. */
    suspend fun addTopics(noteId: String, titles: List<String>) {
        val o = db.outlineDao().getForNote(noteId) ?: return
        val (preamble, linked) = OutlineParser.parseEnvelope(
            o.sectionsJson.takeIf { it.isNotBlank() } ?: "[]"
        )
        val clean = titles.map { it.trim().take(120) }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        val updated = linked + clean.mapIndexed { i, t ->
            OutlineSection(t, null, linked.size + i, "", 0)
        }
        db.outlineDao().upsert(
            o.copy(
                sectionsJson = OutlineParser.toJson(updated, preamble),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Renomeia o tema do esboço (título da nota é mestre). */
    suspend fun updateTitle(noteId: String, title: String) {
        val o = db.outlineDao().getForNote(noteId) ?: return
        val t = title.trim().take(140)
        if (t.isBlank() || t == o.title) return
        db.outlineDao().upsert(o.copy(title = t, updatedAt = System.currentTimeMillis()))
    }
}
