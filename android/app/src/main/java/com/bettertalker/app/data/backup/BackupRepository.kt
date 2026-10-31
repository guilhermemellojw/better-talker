package com.bettertalker.app.data.backup

import android.content.Context
import android.net.Uri
import com.bettertalker.app.BuildConfig
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.TransactionRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export/import local dos dados do usuário.
 *
 * O ZIP NÃO carrega arquivos de publicações nem passagens indexadas. Anexos
 * viajam só como metadados; no import, se o arquivo local ainda existir, ele
 * é preservado; senão o anexo fica "failed" com instrução para baixar de
 * novo na Biblioteca.
 */
class BackupRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val transactionRunner: TransactionRunner = RoomTransactionRunner(db),
) {
    suspend fun exportTo(uri: Uri): BackupExportResult = withContext(Dispatchers.IO) {
        val snapshot = collectSnapshot()
        val bytes = encode(snapshot)
        val out = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("não foi possível abrir o destino do backup")
        out.use { it.write(bytes) }
        BackupExportResult(snapshot.counts(), bytes.size.toLong())
    }

    suspend fun readFrom(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("não foi possível abrir o backup")
        input.use {
            BackupCodec.readZip(it, db.openHelper.readableDatabase.version)
        }
    }

    suspend fun import(preview: BackupPreview): BackupImportResult =
        withContext(Dispatchers.IO) {
            // Safety net: exporta o estado atual ANTES de qualquer escrita.
            val safety = writeSafetyBackup()
            transactionRunner.run {
                applySnapshot(preview.snapshot)
            }
            BackupImportResult(preview.snapshot.counts(), safety.absolutePath)
        }

    private fun encode(snapshot: BackupSnapshot): ByteArray =
        BackupCodec.encodeZip(
            snapshot = snapshot,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            dbVersion = db.openHelper.readableDatabase.version,
        )

    private suspend fun collectSnapshot(): BackupSnapshot {
        val notes = db.noteDao().allIncludingTrashed()
        val sections = notes.flatMap { db.speechSectionDao().forNote(it.id) }
        val subPoints = if (sections.isEmpty()) {
            emptyList()
        } else {
            db.subPointDao().forSections(sections.map { it.id })
        }
        val attachments = db.attachmentDao().all()

        val s34Outlines = mutableListOf<com.bettertalker.app.data.db.S34OutlineEntity>()
        val s34Sections = mutableListOf<com.bettertalker.app.data.db.S34SectionEntity>()
        val s34Subsections = mutableListOf<com.bettertalker.app.data.db.S34SubsectionEntity>()
        val s34References = mutableListOf<com.bettertalker.app.data.db.S34ReferenceEntity>()
        attachments.forEach { attachment ->
            val outline = db.s34Dao().outlineBySource(attachment.id) ?: return@forEach
            s34Outlines += outline
            val outlineSections = db.s34Dao().sectionsOf(outline.id)
            s34Sections += outlineSections
            if (outlineSections.isNotEmpty()) {
                s34Subsections += db.s34Dao().subsectionsOf(outlineSections.map { it.id })
            }
            s34References += db.s34Dao().referencesOf(outline.id)
        }

        return BackupSnapshot(
            folders = db.folderDao().all(),
            notes = notes,
            attachments = attachments.map { it.toBackupAttachment() },
            outlines = db.outlineDao().all(),
            speechSections = sections,
            subPoints = subPoints,
            s34Outlines = s34Outlines,
            s34Sections = s34Sections,
            s34Subsections = s34Subsections,
            s34References = s34References,
            tombstones = db.tombstoneDao().all(),
        )
    }

    private suspend fun writeSafetyBackup(): File {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "pre-import-$stamp.zip")
        file.writeBytes(encode(collectSnapshot()))
        // Mantém os 5 mais recentes; o resto é descartável.
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
        return file
    }

    private suspend fun applySnapshot(snapshot: BackupSnapshot) {
        // Ordem respeita as referências lógicas (não há FK em todas as tabelas,
        // mas manter a ordem deixa o import previsível).
        snapshot.folders.forEach { db.folderDao().upsert(it) }
        snapshot.notes.forEach { db.noteDao().upsert(it) }
        snapshot.attachments.forEach { db.attachmentDao().upsert(it.toEntity()) }
        snapshot.outlines.forEach { db.outlineDao().upsert(it) }

        if (snapshot.speechSections.isNotEmpty()) {
            db.speechSectionDao().upsertAll(snapshot.speechSections)
        }
        if (snapshot.subPoints.isNotEmpty()) {
            db.subPointDao().upsertAll(snapshot.subPoints)
        }

        snapshot.s34Outlines.forEach { outline ->
            // Recria o outline importado sem deixar seções/refs antigas.
            db.s34Dao().deleteRefsOf(outline.id)
            val oldSections = db.s34Dao().sectionsOf(outline.id)
            if (oldSections.isNotEmpty()) {
                db.s34Dao().deleteSubsOf(oldSections.map { it.id })
            }
            db.s34Dao().deleteSectionsOf(outline.id)
            db.s34Dao().deleteOutline(outline.id)

            db.s34Dao().putOutline(outline)
            val sections = snapshot.s34Sections.filter { it.outlineId == outline.id }
            if (sections.isNotEmpty()) db.s34Dao().putSections(sections)
            val sectionIds = sections.mapTo(HashSet()) { it.id }
            val subsections = snapshot.s34Subsections.filter { it.sectionId in sectionIds }
            if (subsections.isNotEmpty()) db.s34Dao().putSubsections(subsections)
            val refs = snapshot.s34References.filter { it.outlineId == outline.id }
            if (refs.isNotEmpty()) db.s34Dao().putReferences(refs)
        }

        snapshot.tombstones.forEach { db.tombstoneDao().put(it) }
    }

    private fun AttachmentEntity.toBackupAttachment() = BackupAttachment(
        id = id,
        noteId = noteId,
        fileName = fileName,
        kind = kind,
        sizeBytes = sizeBytes,
        baseSlot = baseSlot,
        sourceUrl = sourceUrl,
        sourceType = sourceType,
        symbol = symbol,
        addedAt = addedAt,
    )

    private suspend fun BackupAttachment.toEntity(): AttachmentEntity {
        val existing = db.attachmentDao().get(id)
        val local = existing?.appPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
        return if (existing != null && local != null && local.exists()) {
            // O arquivo já está neste aparelho: preserva appPath/indexed/status.
            existing.copy(
                noteId = noteId,
                fileName = fileName,
                kind = kind,
                sizeBytes = sizeBytes,
                baseSlot = baseSlot,
                sourceUrl = sourceUrl ?: existing.sourceUrl,
                sourceType = sourceType,
                symbol = symbol ?: existing.symbol,
            )
        } else {
            AttachmentEntity(
                id = id,
                noteId = noteId,
                fileName = fileName,
                kind = kind,
                sizeBytes = sizeBytes,
                appPath = "",
                indexed = false,
                addedAt = addedAt,
                baseSlot = baseSlot,
                status = "failed",
                error = "arquivo não incluído no backup; baixe novamente na Biblioteca",
                downloadId = -1L,
                sourceUrl = sourceUrl,
                sourceType = sourceType,
                symbol = symbol,
            )
        }
    }
}
