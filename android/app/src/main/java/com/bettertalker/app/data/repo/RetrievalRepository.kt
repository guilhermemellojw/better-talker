package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
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
import com.bettertalker.app.data.domain.expandQuery
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

/**
 * F20: teto por termo da busca guiada. Publicações grandes (it/rsg) têm
 * 100k+ trechos — carregar só o "início" deixava o resto do alfabeto
 * invisível para o retrieval.
 */
private const val TERM_CANDIDATES = 400

/** F20: termos da query usados na seleção de candidatos. */
private const val MAX_TERMS = 6

/**
 * #15d — fallback adaptativo do ContextPack: limiar de relevância
 * (finalScore), mínimo de hits relevantes para o caminho normal e o
 * Top-K de resgate. Abaixo de [MIN_RELEVANT_HITS] relevantes, a query é
 * expandida com os sinônimos curados (QueryExpansion) e o retrieve sobe
 * para [FALLBACK_TOP_K]. Calibração: o finalScore já embute o piso da
 * fonte (SOURCE_WEIGHT × prioridade ≈ 0,09-0,10), então 0,15 exige
 * ~20%+ de cobertura dos termos da query.
 *
 * #15d-fix: um hit só conta como relevante se, além do score, casar
 * [MIN_MATCHED_TERMS] termos ou mais — o Discovery provou que 1 match de
 * termo raro (ex.: "explicar", idf 0.68) + piso BIBLE (0.10) = 0.225
 * passava o gate sozinho e o fallback nunca disparava.
 */
private const val MIN_RELEVANT_SCORE = 0.15
internal const val MIN_MATCHED_TERMS = 2
internal const val MIN_RELEVANT_HITS = 3
private const val FALLBACK_TOP_K = 10

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
    ids: List<String>,
    query: String
): Pair<List<com.bettertalker.app.data.domain.Passage>, Map<String, String?>> {
    if (ids.isEmpty()) return emptyList<com.bettertalker.app.data.domain.Passage>() to emptyMap()
    // F20: candidatos guiados pelos termos da query (LIKE no texto
    // normalizado). Sem match (ou termos curtos), cai no teto do SQL — o
    // corpus inteiro (it+rsg = 240k+ trechos) nunca é materializado.
    val terms = HybridRetrieval.tokenize(query).distinct().take(MAX_TERMS)
    val byId = LinkedHashMap<String, PassageEntity>()
    for (t in terms) {
        if (byId.size >= MAX_CANDIDATES) break
        passageDao.searchLikeIn(ids, t, TERM_CANDIDATES).forEach { byId.putIfAbsent(it.id, it) }
    }
    val entities = if (byId.isNotEmpty()) {
        byId.values.take(MAX_CANDIDATES)
    } else {
        passageDao.forAttachmentsLimited(ids, MAX_CANDIDATES)
    }
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
        val (passages, titles) = loadCandidates(passageDao, atts, scope.contentSourceIds, query)
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
        val (passages, titles) = loadCandidates(passageDao, atts, scope.trainingSourceIds, query)
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
    private val training: TrainingRepository,
    /** #15d: log injetável — testes JVM não têm android.util.Log. */
    private val log: (String) -> Unit = { android.util.Log.i("Retrieval", it) }
) : ContextPackRepository {
    override suspend fun buildPack(
        query: String,
        scope: RetrievalScope,
        includeTraining: Boolean,
        trainingCategory: TrainingCategory?
    ): ContextPack {
        // #15d: caminho normal = Top-K 5. O fallback adaptativo só entra
        // quando o retrieve padrão devolve poucos trechos RELEVANTES (o
        // TF-IDF não casou com o vocabulário do acervo).
        // #15d-fix: "relevante" = score >= MIN_RELEVANT_SCORE **E**
        // cobertura >= MIN_MATCHED_TERMS (1 match raro de superfície não
        // conta mais como relevante).
        val baseHits = retrieval.retrieve(query, scope).hits
        val relevant = baseHits.count {
            it.finalScore >= MIN_RELEVANT_SCORE &&
                it.matchedTerms.size >= MIN_MATCHED_TERMS
        }
        val content = if (relevant < MIN_RELEVANT_HITS) {
            val expandedQuery = expandQuery(query)
            val expandedHits = retrieval.retrieve(expandedQuery, scope, FALLBACK_TOP_K).hits
            log("fallback: base=${baseHits.size} expanded=${expandedHits.size}")
            // Merge: preserva os hits base (nunca substitui) + novos com
            // score mínimo; dedupe por id; ordem por score.
            (baseHits + expandedHits.filter { it.finalScore >= MIN_RELEVANT_SCORE })
                .distinctBy { it.passage.id }
                .sortedByDescending { it.finalScore }
        } else {
            baseHits
        }
        val trainingHits = if (includeTraining) {
            training.retrieveTraining(query, trainingCategory, scope).hits
        } else emptyList()
        // #15d-escopo: expõe a contagem de relevantes para a sugestão de vínculo.
        return ContextPacks.fromCandidates(content, trainingHits, relevantCount = relevant)
    }
}
