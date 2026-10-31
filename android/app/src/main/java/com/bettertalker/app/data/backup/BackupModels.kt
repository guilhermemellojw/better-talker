package com.bettertalker.app.data.backup

import com.bettertalker.app.data.db.FolderEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.db.S34OutlineEntity
import com.bettertalker.app.data.db.S34ReferenceEntity
import com.bettertalker.app.data.db.S34SectionEntity
import com.bettertalker.app.data.db.S34SubsectionEntity
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.SubPointEntity
import com.bettertalker.app.data.db.TombstoneEntity

/**
 * Formato de backup local (ZIP + JSON).
 *
 * Inclui o que é do usuário: pastas, notas, esboços, seções/subpontos, S-34,
 * metadados de anexos e tombstones. NÃO inclui:
 * - arquivos das publicações (2,4 GB) nem passagens indexadas;
 * - histórico do Copilot;
 * - chave da API/Keystore nem qualquer segredo.
 */
data class BackupSnapshot(
    val folders: List<FolderEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val attachments: List<BackupAttachment> = emptyList(),
    val outlines: List<OutlineEntity> = emptyList(),
    val speechSections: List<SpeechSectionEntity> = emptyList(),
    val subPoints: List<SubPointEntity> = emptyList(),
    val s34Outlines: List<S34OutlineEntity> = emptyList(),
    val s34Sections: List<S34SectionEntity> = emptyList(),
    val s34Subsections: List<S34SubsectionEntity> = emptyList(),
    val s34References: List<S34ReferenceEntity> = emptyList(),
    val tombstones: List<TombstoneEntity> = emptyList(),
) {
    fun counts(): BackupCounts = BackupCounts(
        folders = folders.size,
        notes = notes.size,
        attachments = attachments.size,
        outlines = outlines.size,
        speechSections = speechSections.size,
        subPoints = subPoints.size,
        s34Outlines = s34Outlines.size,
        s34Sections = s34Sections.size,
        s34Subsections = s34Subsections.size,
        s34References = s34References.size,
        tombstones = tombstones.size,
    )
}

/**
 * Anexo no backup: só metadados. `appPath`, `downloadId`, `indexed` e
 * `status` são específicos do aparelho e não viajam. No import, o app
 * tenta manter o arquivo local se ele já existir; senão marca como
 * "failed" com instrução para baixar de novo.
 */
data class BackupAttachment(
    val id: String,
    val noteId: String?,
    val fileName: String,
    val kind: String,
    val sizeBytes: Long,
    val baseSlot: String?,
    val sourceUrl: String?,
    val sourceType: String,
    val symbol: String?,
    val addedAt: Long,
)

data class BackupCounts(
    val folders: Int = 0,
    val notes: Int = 0,
    val attachments: Int = 0,
    val outlines: Int = 0,
    val speechSections: Int = 0,
    val subPoints: Int = 0,
    val s34Outlines: Int = 0,
    val s34Sections: Int = 0,
    val s34Subsections: Int = 0,
    val s34References: Int = 0,
    val tombstones: Int = 0,
) {
    fun toMap(): Map<String, Int> = linkedMapOf(
        "folders" to folders,
        "notes" to notes,
        "attachments" to attachments,
        "outlines" to outlines,
        "speechSections" to speechSections,
        "subPoints" to subPoints,
        "s34Outlines" to s34Outlines,
        "s34Sections" to s34Sections,
        "s34Subsections" to s34Subsections,
        "s34References" to s34References,
        "tombstones" to tombstones,
    )

    companion object {
        fun fromMap(map: Map<String, Int>): BackupCounts = BackupCounts(
            folders = map["folders"] ?: 0,
            notes = map["notes"] ?: 0,
            attachments = map["attachments"] ?: 0,
            outlines = map["outlines"] ?: 0,
            speechSections = map["speechSections"] ?: 0,
            subPoints = map["subPoints"] ?: 0,
            s34Outlines = map["s34Outlines"] ?: 0,
            s34Sections = map["s34Sections"] ?: 0,
            s34Subsections = map["s34Subsections"] ?: 0,
            s34References = map["s34References"] ?: 0,
            tombstones = map["tombstones"] ?: 0,
        )
    }
}

data class BackupManifest(
    val format: String,
    val schemaVersion: Int,
    val appVersion: String,
    val versionCode: Int,
    val dbVersion: Int,
    val exportedAt: Long,
    val counts: BackupCounts,
    val dataSha256: String,
)

data class BackupPreview(
    val manifest: BackupManifest,
    val snapshot: BackupSnapshot,
)

data class BackupExportResult(
    val counts: BackupCounts,
    val bytes: Long,
)

data class BackupImportResult(
    val counts: BackupCounts,
    val safetyBackupPath: String,
)

class BackupValidationException(message: String) : Exception(message)
