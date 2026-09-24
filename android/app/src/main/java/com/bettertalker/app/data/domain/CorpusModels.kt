package com.bettertalker.app.data.domain

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.OutlineSection

/**
 * Modelos de domínio (puros, sem Room) equivalentes aos tipos web:
 * Speech/SpeechBlock ~ Note+Outline, Publication ~ Attachment,
 * Passage ~ PassageEntity. Diferenças documentadas em docs/fase8-native.md.
 */

data class SpeechBlock(
    val id: String,
    val speechId: String,
    val order: Int,
    val minutes: Int?,
    val title: String,
    val contentHtml: String,
    val plainText: String
)

data class Speech(
    val id: String,
    val title: String,
    val blocks: List<SpeechBlock>,
    val updatedAt: Long
)

data class Publication(
    val id: String,
    val fileName: String,
    val title: String,
    val kind: String,
    val indexed: Boolean,
    val addedAt: Long,
    val sourceType: SourceType,
    val symbol: String?,
    val baseSlot: String?
)

data class Passage(
    val id: String,
    val pubId: String,
    val text: String,
    val normalizedText: String,
    val ref: String,
    val section: String,
    val page: Int?,
    val paragraph: Int?,
    val order: Int,
    val sourceType: SourceType,
    val symbol: String?,
    val trainingCategory: TrainingCategory
)

data class EvidenceSource(
    val id: String,
    val reference: String,
    val text: String,
    val sourceType: SourceType,
    val publication: String?,
    val section: String?,
    val paragraph: Int?,
    val page: Int?,
    val trainingCategory: TrainingCategory = TrainingCategory.UNKNOWN,
    val score: Double = 0.0,
    val matchedTerms: List<String> = emptyList(),
    val foundBy: List<String> = emptyList()
)

data class RetrievalScope(
    val contentSourceIds: List<String>,
    val trainingSourceIds: List<String>
) {
    fun isEmpty(): Boolean = contentSourceIds.isEmpty() && trainingSourceIds.isEmpty()
}

enum class RetrievalStatus { OK, INSUFFICIENT_SCOPE, EMPTY_CORPUS }

data class RetrievalCandidate(
    val passage: Passage,
    val publicationTitle: String?,
    val lexicalScore: Double,
    val metadataScore: Double,
    val finalScore: Double,
    val matchedTerms: List<String>,
    val foundBy: List<String>
)

data class RetrievalResult(
    val status: RetrievalStatus,
    val hits: List<RetrievalCandidate>
)

data class ContextPack(
    val contentSources: List<EvidenceSource>,
    val trainingSources: List<EvidenceSource>
)

// ---------- Adapters Entity -> Domain (sem inferência: ausente vira null) ----------

fun AttachmentEntity.toPublication(): Publication = Publication(
    id = id,
    fileName = fileName,
    title = fileName,
    kind = kind,
    indexed = indexed,
    addedAt = addedAt,
    sourceType = if (sourceType.isNotBlank()) SourceType.fromSerial(sourceType)
    else SourceType.fromBaseSlot(baseSlot),
    symbol = symbol,
    baseSlot = baseSlot
)

fun PassageEntity.toPassage(
    publicationTitle: String? = null,
    sourceType: SourceType = SourceType.CONTENT,
    symbol: String? = null
): Passage = Passage(
    id = id,
    pubId = attachmentId,
    text = text,
    normalizedText = normalized,
    ref = ref,
    section = section,
    page = page,
    paragraph = paragraph,
    order = ord,
    sourceType = sourceType,
    symbol = symbol,
    trainingCategory = TrainingCategory.fromSerial(trainingCategory)
)

/** Nota + esboço vinculado vistos como Speech (blocos = seções do outline). */
fun NoteEntity.toSpeech(outline: OutlineEntity?): Speech {
    val sections: List<OutlineSection> =
        outline?.let { OutlineParser.fromJson(it.sectionsJson) } ?: emptyList()
    val blocks = sections.mapIndexed { i, s ->
        SpeechBlock(
            id = "$id-sec-$i",
            speechId = id,
            order = i,
            minutes = s.minutes,
            title = s.title,
            contentHtml = s.body,
            plainText = s.body
        )
    }
    return Speech(id = id, title = title, blocks = blocks, updatedAt = updatedAt)
}
