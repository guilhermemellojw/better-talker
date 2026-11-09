package com.bettertalker.app.data.bible

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.domain.bible.BiblePassageSource
import com.bettertalker.app.domain.bible.BibleSource

/**
 * v5.2 — leitor sobre o índice Room. Prefere a TNM base
 * (`baseReady("nwt")`); sem ela, aceita qualquer Bíblia indexada.
 * Nenhuma migração ou schema novo: só consultas.
 */
class RoomBiblePassageSource(
    private val passageDao: PassageDao,
    private val attachmentDao: AttachmentDao,
) : BiblePassageSource {

    override suspend fun preferredSource(): BibleSource? {
        val tnm = attachmentDao.baseReady("nwt")
        val all = attachmentDao.all()
        return selectBibleSource(tnm, all)
    }

    override suspend fun texts(
        sourceId: String,
        canonicalRefs: List<String>,
    ): Map<String, String> {
        if (canonicalRefs.isEmpty()) return emptyMap()
        return passageDao.findByRefsIn(sourceId, canonicalRefs)
            .filter { it.ref.isNotBlank() && it.text.isNotBlank() }
            .associate { it.ref to it.text }
    }
}

/** Pura/testável: TNM base primeiro; depois qualquer Bíblia indexada. */
internal fun selectBibleSource(
    tnm: AttachmentEntity?,
    all: List<AttachmentEntity>,
): BibleSource? {
    val chosen = tnm ?: all.firstOrNull {
        it.indexed && SourceType.fromSerial(it.sourceType) == SourceType.BIBLE
    } ?: return null
    val isTnm = chosen.baseSlot == "nwt" || chosen.symbol == "nwt"
    val fallbackLabel = chosen.fileName.substringBeforeLast('.').ifBlank { "Bíblia" }
    return BibleSource(
        attachmentId = chosen.id,
        label = if (isTnm) "TNM" else fallbackLabel,
        isTnm = isTnm,
    )
}
