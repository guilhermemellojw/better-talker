package com.bettertalker.app.data.publication

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.PublicationUnitDao
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.detectSymbol

/**
 * 5.6a: cria o índice de unidades sob demanda para anexos indexados antes da
 * v26. Não reextrai o JWPUB: deriva das `passages` já existentes.
 *
 * Idempotente: se o anexo já tem unidades, devolve o índice atual.
 */
object PublicationUnitBackfill {

    suspend fun ensureFor(
        attachmentId: String,
        db: AppDatabase,
    ): List<PublicationUnitEntity> = ensureFor(
        attachmentId = attachmentId,
        attachments = db.attachmentDao(),
        passages = db.passageDao(),
        units = db.publicationUnitDao(),
    )

    suspend fun ensureFor(
        attachmentId: String,
        attachments: AttachmentDao,
        passages: PassageDao,
        units: PublicationUnitDao,
    ): List<PublicationUnitEntity> {
        val existing = units.forAttachment(attachmentId)
        if (existing.isNotEmpty()) return existing

        val attachment = attachments.get(attachmentId) ?: return emptyList()
        val rows = passages.forAttachments(listOf(attachmentId))
        val built = buildIfMissing(attachment, rows, existing)
        if (built.isNotEmpty()) {
            units.replaceForAttachment(attachmentId, built)
        }
        return built
    }

    /**
     * Parte pura do backfill: não toca no banco e devolve [existing] quando já
     * há índice. Usada pelo fluxo real e pelos testes de idempotência.
     */
    internal fun buildIfMissing(
        attachment: AttachmentEntity,
        passages: List<PassageEntity>,
        existing: List<PublicationUnitEntity> = emptyList(),
    ): List<PublicationUnitEntity> {
        if (existing.isNotEmpty()) return existing
        if (passages.isEmpty()) return emptyList()

        val fileKind = detectKind(attachment.fileName)
        val pubKind = publicationKindFor(detectSymbol(attachment.fileName), fileKind)
        return PublicationUnitBuilder.build(
            attachmentId = attachment.id,
            symbol = detectSymbol(attachment.fileName),
            pubKind = pubKind,
            passages = passages,
        )
    }
}
