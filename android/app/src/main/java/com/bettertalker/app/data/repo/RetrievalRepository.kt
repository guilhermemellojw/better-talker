package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.domain.ContextPack
import com.bettertalker.app.data.domain.ContextPacks
import com.bettertalker.app.data.domain.HybridRetrieval
import com.bettertalker.app.data.domain.Publication
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.toSpeech
import com.bettertalker.app.data.domain.RetrievalResult
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.RetrievalStatus
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.Speech
import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.domain.TrainingClassifier
import com.bettertalker.app.data.domain.toPassage
import com.bettertalker.app.data.domain.toPublication

/**
 * Interfaces de domínio — a UI/ViewModel depende destas, nunca de DAO/Room.
 * Implementações Room abaixo. Repositórios legados (notas, biblioteca, chat)
 * permanecem intocados.
 */
interface SpeechRepository {
    suspend fun getSpeech(noteId: String): Speech?
}

interface PublicationRepository {
    suspend fun publications(): List<Publication>
    suspend fun scope(): RetrievalScope
}

interface RetrievalRepository {
    suspend fun retrieve(query: String, scope: RetrievalScope, limit: Int = 5): RetrievalResult
}

interface TrainingRepository {
    suspend fun retrieveTraining(
        query: String,
        category: TrainingCategory?,
        scope: RetrievalScope,
        limit: Int = 4
    ): RetrievalResult
}

interface ContextPackRepository {
    suspend fun buildPack(
        query: String,
        scope: RetrievalScope,
        includeTraining: Boolean,
        trainingCategory: TrainingCategory? = null
    ): ContextPack
}

/** Máximo de candidatos carregados do escopo (protege memória). */
private const val MAX_CANDIDATES = 2000

private fun sourceTypeOf(a: AttachmentEntity): SourceType =
    if (a.sourceType.isNotBlank()) SourceType.fromSerial(a.sourceType)
    else SourceType.fromBaseSlot(a.baseSlot)

class RoomSpeechRepository(private val db: com.bettertalker.app.data.db.AppDatabase) : SpeechRepository {
    override suspend fun getSpeech(noteId: String): Speech? {
        val note = db.noteDao().get(noteId) ?: return null
        val outline = db.outlineDao().getForNote(noteId)
        return note.toSpeech(outline)
    }
}

class RoomPublicationRepository(
    private val attachmentDao: com.bettertalker.app.data.db.AttachmentDao
) : PublicationRepository {
    override suspend fun publications(): List<Publication> =
        attachmentDao.all().map { it.toPublication() }

    override suspend fun scope(): RetrievalScope {
        val atts = attachmentDao.all().filter { it.indexed }
        val (training, content) = atts.partition { sourceTypeOf(it) == SourceType.TRAINING }
        return RetrievalScope(
            contentSourceIds = content.map { it.id },
            trainingSourceIds = training.map { it.id }
        )
    }
}

private suspend fun loadCandidates(
    passageDao: PassageDao,
    attachmentsById: Map<String, AttachmentEntity>,
    ids: List<String>
): Pair<List<com.bettertalker.app.data.domain.Passage>, Map<String, String?>> {
    if (ids.isEmpty()) return emptyList<com.bettertalker.app.data.domain.Passage>() to emptyMap()
    val entities = passageDao.forAttachments(ids).take(MAX_CANDIDATES)
    val passages = entities.map { e ->
        val att = attachmentsById[e.attachmentId]
        e.toPassage(
            publicationTitle = att?.fileName,
            sourceType = att?.let { sourceTypeOf(it) } ?: SourceType.CONTENT,
            symbol = att?.symbol
        )
    }
    val titles = attachmentsById.mapValues { it.value.fileName }
    return passages to titles
}

private fun toCandidate(
    s: com.bettertalker.app.data.domain.ScoredCandidate
): RetrievalCandidate = RetrievalCandidate(
    passage = s.passage,
    publicationTitle = s.publicationTitle,
    lexicalScore = s.lexicalScore,
    metadataScore = s.metadataScore,
    finalScore = s.finalScore,
    matchedTerms = s.matchedTerms,
    foundBy = s.foundBy
)

class RoomRetrievalRepository(
    private val passageDao: PassageDao,
    private val attachmentDao: com.bettertalker.app.data.db.AttachmentDao
) : RetrievalRepository {
    override suspend fun retrieve(query: String, scope: RetrievalScope, limit: Int): RetrievalResult {
        if (query.isBlank()) return RetrievalResult(RetrievalStatus.INSUFFICIENT_SCOPE, emptyList())
        if (scope.contentSourceIds.isEmpty()) {
            // §10: sem fontes autorizadas => insuficiência, nunca varredura global.
            return RetrievalResult(RetrievalStatus.INSUFFICIENT_SCOPE, emptyList())
        }
        val atts = attachmentDao.all().associateBy { it.id }
        val (passages, titles) = loadCandidates(passageDao, atts, scope.contentSourceIds)
        if (passages.isEmpty()) return RetrievalResult(RetrievalStatus.EMPTY_CORPUS, emptyList())
        val hits = HybridRetrieval.rank(query, passages, titles, limit).map(::toCandidate)
        return RetrievalResult(RetrievalStatus.OK, hits)
    }
}

class RoomTrainingRepository(
    private val passageDao: PassageDao,
    private val attachmentDao: com.bettertalker.app.data.db.AttachmentDao
) : TrainingRepository {
    override suspend fun retrieveTraining(
        query: String,
        category: TrainingCategory?,
        scope: RetrievalScope,
        limit: Int
    ): RetrievalResult {
        if (query.isBlank()) return RetrievalResult(RetrievalStatus.INSUFFICIENT_SCOPE, emptyList())
        if (scope.trainingSourceIds.isEmpty()) {
            return RetrievalResult(RetrievalStatus.INSUFFICIENT_SCOPE, emptyList())
        }
        val atts = attachmentDao.all().associateBy { it.id }
        val (passages, titles) = loadCandidates(passageDao, atts, scope.trainingSourceIds)
        if (passages.isEmpty()) return RetrievalResult(RetrievalStatus.EMPTY_CORPUS, emptyList())
        val ranked = HybridRetrieval.rank(query, passages, titles, maxOf(limit * 3, 6)).map(::toCandidate)
        if (category == null || category == TrainingCategory.UNKNOWN) {
            return RetrievalResult(RetrievalStatus.OK, ranked.take(limit.coerceAtLeast(0)))
        }
        val boosted = HybridRetrieval.applyCategoryBoost(ranked, category) { c ->
            // Categoria gravada prevalece; UNKNOWN cai no classificador (backfill).
            val stored = c.passage.trainingCategory
            if (stored != TrainingCategory.UNKNOWN) stored
            else TrainingClassifier.classify(c.passage.section, c.publicationTitle, c.passage.text)
        }
        return RetrievalResult(RetrievalStatus.OK, boosted.take(limit.coerceAtLeast(0)))
    }
}

class RoomContextPackRepository(
    private val retrieval: RetrievalRepository,
    private val training: TrainingRepository
) : ContextPackRepository {
    override suspend fun buildPack(
        query: String,
        scope: RetrievalScope,
        includeTraining: Boolean,
        trainingCategory: TrainingCategory?
    ): ContextPack {
        val content = retrieval.retrieve(query, scope).hits
        val trainingHits = if (includeTraining) {
            training.retrieveTraining(query, trainingCategory, scope).hits
        } else emptyList()
        return ContextPacks.fromCandidates(content, trainingHits)
    }
}
