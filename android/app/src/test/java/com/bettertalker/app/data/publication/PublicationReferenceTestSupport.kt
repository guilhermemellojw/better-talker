package com.bettertalker.app.data.publication

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.PublicationUnitDao
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.planning.sectionMatchesNeedle
import com.bettertalker.app.data.util.RefDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Fixtures sintéticas: nenhum texto de publicação protegida. */
internal class FakeAttachmentDao(
    var rows: List<AttachmentEntity> = emptyList(),
) : AttachmentDao {
    var failAll: Boolean = false

    /** Simula o anexo removido entre a seleção e a leitura fixada. */
    var getReturnsNull: Boolean = false

    /** Nenhuma resolução pode escrever no acervo. */
    var writeCount: Int = 0
        private set

    override fun observe(): Flow<List<AttachmentEntity>> = MutableStateFlow(rows)
    override fun observeForNote(noteId: String): Flow<List<AttachmentEntity>> =
        MutableStateFlow(rows.filter { it.noteId == noteId })

    override suspend fun all(): List<AttachmentEntity> {
        if (failAll) throw IllegalStateException("falha de leitura simulada")
        return rows
    }

    override suspend fun get(id: String): AttachmentEntity? =
        if (getReturnsNull) null else rows.firstOrNull { it.id == id }

    override suspend fun upsert(a: AttachmentEntity) {
        writeCount++
        rows = rows.filter { it.id != a.id } + a
    }

    override suspend fun update(a: AttachmentEntity) {
        writeCount++
        rows = rows.filter { it.id != a.id } + a
    }

    override suspend fun setIndexed(id: String, indexed: Boolean) {
        writeCount++
        rows = rows.map { if (it.id == id) it.copy(indexed = indexed) else it }
    }

    override suspend fun setStatus(id: String, indexed: Boolean, status: String, error: String?) {
        writeCount++
        rows = rows.map {
            if (it.id == id) it.copy(indexed = indexed, status = status, error = error) else it
        }
    }

    override suspend fun setBaseSlot(id: String, slot: String?) {
        writeCount++
        rows = rows.map { if (it.id == id) it.copy(baseSlot = slot) else it }
    }

    override suspend fun setNote(id: String, noteId: String?) {
        writeCount++
        rows = rows.map { if (it.id == id) it.copy(noteId = noteId) else it }
    }

    override suspend fun baseReady(slot: String): AttachmentEntity? =
        rows.firstOrNull { it.baseSlot == slot && it.indexed }

    override suspend fun delete(id: String) {
        writeCount++
        rows = rows.filter { it.id != id }
    }

    override suspend fun setSourceMeta(id: String, sourceType: String, symbol: String?) {
        writeCount++
        rows = rows.map {
            if (it.id == id) it.copy(sourceType = sourceType, symbol = symbol) else it
        }
    }
}

internal class FakePassageDao(
    var rows: List<PassageEntity> = emptyList(),
) : PassageDao {
    var writeCount: Int = 0
        private set

    /** Simula uma consulta Room que ainda usa substring (sem boundary). */
    var bySectionIgnoresBoundary: Boolean = false

    override suspend fun insertAll(items: List<PassageEntity>) {
        writeCount++
        rows = rows + items
    }

    override suspend fun forAttachment(attachmentId: String): List<PassageEntity> =
        rows.filter { it.attachmentId == attachmentId }

    override suspend fun deleteForAttachment(attachmentId: String) {
        writeCount++
        rows = rows.filter { it.attachmentId != attachmentId }
    }
    override suspend fun searchLike(norm: String, limit: Int): List<PassageEntity> =
        rows.filter { it.normalized.contains(norm) }.take(limit)
    override suspend fun searchLikeIn(
        ids: List<String>,
        norm: String,
        limit: Int,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId in ids && it.normalized.contains(norm)
    }.take(limit)
    override suspend fun indexedAttachmentIds(): List<String> =
        rows.map { it.attachmentId }.distinct()
    override suspend fun findByRef(ref: String): PassageEntity? =
        rows.firstOrNull { it.ref == ref }
    override suspend fun findByRefIn(attachmentId: String, ref: String): PassageEntity? =
        rows.firstOrNull { it.attachmentId == attachmentId && it.ref == ref }
    override suspend fun findByRefsIn(
        attachmentId: String,
        refs: List<String>,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId == attachmentId && it.ref in refs
    }
    override suspend fun findByTextInAttachment(attachmentId: String, text: String, limit: Int): List<PassageEntity> =
        rows.filter { it.attachmentId == attachmentId && it.text == text }.take(limit)
    override suspend fun findByRefPrefixIn(
        attachmentId: String,
        prefix: String,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId == attachmentId && it.ref.startsWith(prefix.removeSuffix("%"))
    }.sortedBy { it.ord }
    override suspend fun forAttachments(ids: List<String>): List<PassageEntity> =
        rows.filter { it.attachmentId in ids }
            .sortedWith(compareBy({ it.attachmentId }, { it.ord }))
    override suspend fun forAttachmentsLimited(
        ids: List<String>,
        limit: Int,
    ): List<PassageEntity> = forAttachments(ids).take(limit)
    override suspend fun betweenOrd(
        attachmentId: String,
        from: Int,
        to: Int,
        limit: Int,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId == attachmentId && it.ord in from..to
    }.sortedBy { it.ord }.take(limit)
    override suspend fun nextTitleAfter(attachmentId: String, ord: Int): PassageEntity? =
        rows.filter {
            it.attachmentId == attachmentId && it.text.startsWith("# ") && it.ord > ord
        }.minByOrNull { it.ord }
    override suspend fun bySection(
        attachmentId: String,
        needle: String,
        limit: Int,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId == attachmentId &&
            if (bySectionIgnoresBoundary) {
                it.section.contains(needle, ignoreCase = true)
            } else {
                sectionMatchesNeedle(it.section, needle)
            }
    }.sortedBy { it.ord }.take(limit)
    override suspend fun byPageParagraph(
        attachmentId: String,
        page: Int,
        start: Int,
        end: Int,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId == attachmentId && it.page == page &&
            it.paragraph?.let { p -> p in start..end } == true
    }.sortedWith(compareBy({ it.paragraph }, { it.ord }))
    override suspend fun byPage(
        attachmentId: String,
        start: Int,
        end: Int,
        limit: Int,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId == attachmentId && it.page?.let { p -> p in start..end } == true
    }.sortedWith(compareBy({ it.page }, { it.ord })).take(limit)
    override suspend fun byParagraphRange(
        attachmentId: String,
        start: Int,
        end: Int,
        limit: Int,
    ): List<PassageEntity> = rows.filter {
        it.attachmentId == attachmentId &&
            it.paragraph?.let { p -> p in start..end } == true
    }.sortedBy { it.ord }.take(limit)
}

internal class FakePublicationUnitDao(
    var rows: List<PublicationUnitEntity> = emptyList(),
) : PublicationUnitDao {
    override suspend fun forAttachment(attachmentId: String): List<PublicationUnitEntity> =
        rows.filter { it.attachmentId == attachmentId }.sortedBy { it.ord }
    override suspend fun unitAt(attachmentId: String, ord: Int): PublicationUnitEntity? =
        rows.firstOrNull {
            it.attachmentId == attachmentId && it.startOrd <= ord && it.endOrd >= ord
        }
    override suspend fun unitsByKind(
        attachmentId: String,
        kind: String,
    ): List<PublicationUnitEntity> = rows.filter {
        it.attachmentId == attachmentId && it.kind == kind
    }.sortedBy { it.ord }
    override suspend fun countForAttachment(attachmentId: String): Int =
        rows.count { it.attachmentId == attachmentId }
    override suspend fun insertAll(items: List<PublicationUnitEntity>) { rows = rows + items }
    override suspend fun deleteForAttachment(attachmentId: String) {
        rows = rows.filter { it.attachmentId != attachmentId }
    }
}

internal fun attachment(
    id: String,
    fileName: String,
    indexed: Boolean = true,
    status: String = "ready",
    baseSlot: String? = null,
    symbol: String? = null,
    sha256: String? = null,
    error: String? = null,
): AttachmentEntity = AttachmentEntity(
    id = id,
    noteId = null,
    fileName = fileName,
    kind = fileName.substringAfterLast('.', "bin").lowercase(),
    sizeBytes = 1L,
    appPath = "/tmp/$fileName",
    indexed = indexed,
    addedAt = 1L,
    baseSlot = baseSlot,
    status = status,
    error = error,
    sha256 = sha256,
    symbol = symbol,
)

internal fun passage(
    id: String,
    attachmentId: String,
    text: String,
    section: String = "",
    ref: String = "",
    page: Int? = null,
    paragraph: Int? = null,
    ord: Int = 0,
): PassageEntity = PassageEntity(
    id = id,
    attachmentId = attachmentId,
    text = text,
    normalized = text.lowercase(),
    section = section,
    ref = ref,
    page = page,
    paragraph = paragraph,
    ord = ord,
)

internal fun unit(
    id: String,
    attachmentId: String,
    kind: String = "LESSON",
    number: String? = null,
    title: String,
    startOrd: Int = 0,
    endOrd: Int = 1,
): PublicationUnitEntity = PublicationUnitEntity(
    id = id,
    attachmentId = attachmentId,
    kind = kind,
    number = number,
    title = title,
    canonicalKey = "test:$id",
    ord = 0,
    startOrd = startOrd,
    endOrd = endOrd,
)

internal fun detected(raw: String): RefDetector.DetectedRef =
    RefDetector.detect(raw).firstOrNull()
        ?: syntheticSymbol(raw)
        ?: error("fixture sem referência reconhecida: $raw")

private fun syntheticSymbol(raw: String): RefDetector.DetectedRef? {
    val token = raw.trim()
    if (token != "it-1") return null
    return RefDetector.DetectedRef(
        raw = token,
        kind = RefDetector.Kind.BOOK,
        pubKey = "it-1",
        editionKey = "book|it-1",
        label = "Estudo Perspicaz das Escrituras, Volume 1",
    )
}

internal fun occurrence(raw: String): PublicationReferenceOccurrence {
    val ref = detected(raw)
    return PublicationReferenceOccurrence(
        raw = raw,
        start = 0,
        endExclusive = raw.length,
        publicationKey = ref.pubKey,
        titleHint = null,
        editionKey = ref.editionKey,
        article = ref.article,
        chapter = ref.chapter,
        page = ref.page,
        pageEnd = ref.pageEnd,
        paragraph = ref.paragraph,
        paragraphEnd = ref.paragraphEnd,
        detection = ref,
    )
}
