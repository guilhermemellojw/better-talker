package com.bettertalker.app.data.library

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.domain.HybridRetrieval
import com.bettertalker.app.data.domain.RetrievalCandidate
import com.bettertalker.app.data.domain.RetrievalScope
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.domain.toPassage
import com.bettertalker.app.data.publication.PublicationUnitKind
import com.bettertalker.app.data.repo.RetrievalRepository
import com.bettertalker.app.data.repo.RoomRetrievalRepository
import com.bettertalker.app.data.repo.RoomTrainingRepository
import com.bettertalker.app.data.repo.TrainingRepository
import com.bettertalker.app.data.util.detectSymbol
import com.bettertalker.app.data.util.normalizeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 5.6b: ferramenta sob demanda da Biblioteca para o assistente global.
 *
 * Não pré-carrega o acervo no prompt: resolve edição/unidade, faz retrieval
 * restrito à publicação (até [MAX_SOURCES]), cai para o conteúdo geral se não
 * houver hit nela e devolve fontes citáveis. be/th usam o trilho TRAINING.
 */
class GlobalLibraryTool(
    private val attachments: suspend () -> List<AttachmentEntity>,
    private val retrieval: RetrievalRepository,
    private val training: TrainingRepository,
    private val units: GlobalLibraryUnitLookup,
) {

    suspend fun search(question: String): GlobalLibraryResult = withContext(Dispatchers.IO) {
        val all = attachments()
        val resolved = GlobalLibraryResolver.resolve(question, all)
        when (resolved.status) {
            GlobalLibraryStatus.MISSING,
            GlobalLibraryStatus.INDEXING,
            GlobalLibraryStatus.AMBIGUOUS,
            -> return@withContext resultOf(resolved)
            GlobalLibraryStatus.NO_MATCH -> return@withContext resultOf(resolved)
            GlobalLibraryStatus.READY,
            GlobalLibraryStatus.TRAINING_ONLY,
            -> Unit
        }

        val target = resolved.attachment ?: return@withContext fallbackNoMatch(resolved)
        val publicationTitle = GlobalLibraryResolver.publicationTitle(target, resolved.ref)
        val searchQuery = GlobalLibraryResolver.searchQuery(
            question = question,
            ref = resolved.ref,
            unit = resolved.unit,
            publicationTitle = publicationTitle,
        )
        units.ensure(target.id)
        val allUnits = units.units(target.id)

        if (GlobalLibraryResolver.isTraining(target)) {
            val hits = training.retrieveTraining(
                query = searchQuery,
                category = null,
                scope = RetrievalScope(
                    contentSourceIds = emptyList(),
                    trainingSourceIds = listOf(target.id),
                ),
                limit = MAX_SOURCES,
            ).hits
            val sources = sourcesFromHits(
                hits = hits,
                target = target,
                allUnits = allUnits,
                publicationTitle = publicationTitle,
                training = true,
            )
            if (sources.isEmpty()) {
                return@withContext GlobalLibraryResult(
                    status = GlobalLibraryStatus.NO_MATCH,
                    publicationId = target.id,
                    publicationTitle = publicationTitle,
                    message = GlobalLibraryResolver.NO_MATCH_MESSAGE,
                    trainingOnly = true,
                    libraryIntent = true,
                )
            }
            return@withContext GlobalLibraryResult(
                status = GlobalLibraryStatus.TRAINING_ONLY,
                sources = sources,
                publicationId = target.id,
                publicationTitle = publicationTitle,
                trainingOnly = true,
                libraryIntent = true,
            )
        }

        var hits = retrieval.retrieve(
            query = searchQuery,
            scope = RetrievalScope(
                contentSourceIds = listOf(target.id),
                trainingSourceIds = emptyList(),
            ),
            limit = MAX_SOURCES,
        ).hits

        var labelOverride: Pair<String, String>? = null
        if (resolved.unit != null) {
            val requestedUnit = resolveRequestedUnit(target, allUnits, resolved.unit)
            if (requestedUnit == null) {
                hits = emptyList()
            } else {
                labelOverride = requestedUnit.id to requestedUnitLabel(resolved.unit)
                val inUnit = hits.filter { hit ->
                    unitAtOrd(allUnits, hit.passage.order)?.id == requestedUnit.id
                }
                hits = if (inUnit.isNotEmpty()) {
                    inUnit
                } else {
                    searchInsideUnit(
                        question = searchQuery,
                        target = target,
                        unit = requestedUnit,
                        publicationTitle = publicationTitle,
                    )
                }
            }
        }

        var sources = sourcesFromHits(
            hits = hits,
            target = target,
            allUnits = allUnits,
            publicationTitle = publicationTitle,
            training = false,
            labelOverride = labelOverride,
        )
        var searchedElsewhere = false
        var note: String? = null

        if (sources.isEmpty()) {
            val general = generalContentSources(
                question = searchQuery,
                all = all,
                excludeId = target.id,
            )
            if (general.isNotEmpty()) {
                sources = general
                searchedElsewhere = true
                val elsewhere = general
                    .map { it.publicationTitle }
                    .distinct()
                    .joinToString(", ")
                note = "Não encontrei em $publicationTitle; encontrei em $elsewhere."
            }
        }

        if (sources.isEmpty()) {
            return@withContext GlobalLibraryResult(
                status = GlobalLibraryStatus.NO_MATCH,
                publicationId = target.id,
                publicationTitle = publicationTitle,
                message = GlobalLibraryResolver.NO_MATCH_MESSAGE,
                libraryIntent = true,
            )
        }

        GlobalLibraryResult(
            status = GlobalLibraryStatus.READY,
            sources = sources.take(MAX_SOURCES),
            publicationId = target.id,
            publicationTitle = publicationTitle,
            message = note,
            searchedElsewhere = searchedElsewhere,
            libraryIntent = true,
        )
    }

    suspend fun read(source: GlobalLibrarySource): GlobalLibraryReaderContent =
        withContext(Dispatchers.IO) {
            val rows = units.passages(
                attachmentId = source.attachmentId,
                startOrd = source.startOrd,
                endOrd = source.endOrd,
                limit = MAX_READER_PASSAGES + 1,
            )
            var truncated = rows.size > MAX_READER_PASSAGES
            val body = StringBuilder()
            for (row in rows.take(MAX_READER_PASSAGES)) {
                val text = row.text.trim()
                if (text.isBlank()) continue
                if (body.length + text.length + 2 > MAX_READER_CHARS) {
                    truncated = true
                    break
                }
                if (body.isNotEmpty()) body.append("\n\n")
                body.append(text)
            }
            GlobalLibraryReaderContent(
                publicationTitle = source.publicationTitle,
                unitLabel = source.unitLabel,
                unitTitle = source.unitTitle,
                locationLabel = source.locationLabel,
                body = body.toString().ifBlank { source.snippet },
                training = source.training,
                truncated = truncated,
            )
        }

    private suspend fun generalContentSources(
        question: String,
        all: List<AttachmentEntity>,
        excludeId: String,
    ): List<GlobalLibrarySource> {
        val candidates = all.filter {
            it.id != excludeId &&
                it.indexed &&
                it.status.equals("ready", ignoreCase = true) &&
                sourceTypeOf(it) == SourceType.CONTENT
        }
        if (candidates.isEmpty()) return emptyList()
        val hits = retrieval.retrieve(
            query = question,
            scope = RetrievalScope(
                contentSourceIds = candidates.map { it.id },
                trainingSourceIds = emptyList(),
            ),
            limit = MAX_SOURCES,
        ).hits
        val out = mutableListOf<GlobalLibrarySource>()
        for (hit in hits) {
            val attachment = candidates.firstOrNull { it.id == hit.passage.pubId } ?: continue
            units.ensure(attachment.id)
            val allUnits = units.units(attachment.id)
            val source = sourceFromHit(
                hit = hit,
                target = attachment,
                allUnits = allUnits,
                publicationTitle = GlobalLibraryResolver.publicationTitle(attachment),
                training = false,
                index = out.size + 1,
            ) ?: continue
            out += source
            if (out.size >= MAX_SOURCES) break
        }
        return out
    }

    private suspend fun searchInsideUnit(
        question: String,
        target: AttachmentEntity,
        unit: PublicationUnitEntity,
        publicationTitle: String,
    ): List<RetrievalCandidate> {
        val rows = units.passages(
            attachmentId = target.id,
            startOrd = unit.startOrd,
            endOrd = unit.endOrd,
            limit = MAX_UNIT_SCAN,
        )
        if (rows.isEmpty()) return emptyList()
        val sourceType = sourceTypeOf(target)
        val symbol = detectSymbol(target.fileName)
        val domain = rows.map {
            it.toPassage(
                publicationTitle = publicationTitle,
                sourceType = sourceType,
                symbol = symbol,
            )
        }
        return HybridRetrieval.rank(
            query = question,
            candidates = domain,
            titles = mapOf(target.id to publicationTitle),
            limit = MAX_SOURCES,
        ).map { scored ->
            RetrievalCandidate(
                passage = scored.passage,
                publicationTitle = scored.publicationTitle,
                lexicalScore = scored.lexicalScore,
                metadataScore = scored.metadataScore,
                finalScore = scored.finalScore,
                matchedTerms = scored.matchedTerms,
                foundBy = scored.foundBy,
            )
        }
    }

    private fun sourcesFromHits(
        hits: List<RetrievalCandidate>,
        target: AttachmentEntity,
        allUnits: List<PublicationUnitEntity>,
        publicationTitle: String,
        training: Boolean,
        labelOverride: Pair<String, String>? = null,
    ): List<GlobalLibrarySource> {
        val out = mutableListOf<GlobalLibrarySource>()
        for (hit in hits) {
            if (hit.passage.pubId != target.id) continue
            val source = sourceFromHit(
                hit = hit,
                target = target,
                allUnits = allUnits,
                publicationTitle = publicationTitle,
                training = training,
                index = out.size + 1,
                labelOverride = labelOverride,
            ) ?: continue
            out += source
            if (out.size >= MAX_SOURCES) break
        }
        return out
    }

    private fun sourceFromHit(
        hit: RetrievalCandidate,
        target: AttachmentEntity,
        allUnits: List<PublicationUnitEntity>,
        publicationTitle: String,
        training: Boolean,
        index: Int,
        labelOverride: Pair<String, String>? = null,
    ): GlobalLibrarySource? {
        val unit = unitAtOrd(allUnits, hit.passage.order)
        val ordinal = unit?.let { ordinalOf(it, allUnits) }
        val page = hit.passage.page ?: unit?.startPage
        val pageEnd = if (hit.passage.page == null) unit?.endPage else null
        val label = labelOverride
            ?.takeIf { it.first == unit?.id }
            ?.second
            ?: unitLabel(unit, ordinal)
        return GlobalLibrarySource(
            index = index,
            attachmentId = target.id,
            publicationTitle = publicationTitle,
            unitLabel = label,
            unitTitle = unit?.title
                ?.takeIf { it.isNotBlank() && it != "Documento" },
            page = page,
            pageEnd = pageEnd,
            paragraph = hit.passage.paragraph,
            snippet = hit.passage.text.trim().take(MAX_SNIPPET),
            startOrd = unit?.startOrd ?: hit.passage.order,
            endOrd = unit?.endOrd ?: hit.passage.order,
            unitId = unit?.id,
            training = training,
        )
    }

    private fun resultOf(resolved: GlobalLibraryResolver.Resolution): GlobalLibraryResult {
        val title = resolved.attachment?.let {
            GlobalLibraryResolver.publicationTitle(it, resolved.ref)
        } ?: resolved.ref?.label
        return GlobalLibraryResult(
            status = resolved.status,
            publicationId = resolved.attachment?.id,
            publicationTitle = title,
            candidates = resolved.candidates.map {
                GlobalLibraryCandidate(
                    attachmentId = it.id,
                    title = GlobalLibraryResolver.candidateTitle(it),
                )
            },
            message = resolved.message,
            trainingOnly = resolved.attachment?.let(GlobalLibraryResolver::isTraining) == true,
            libraryIntent = resolved.libraryIntent,
        )
    }

    private fun fallbackNoMatch(resolved: GlobalLibraryResolver.Resolution): GlobalLibraryResult =
        GlobalLibraryResult(
            status = GlobalLibraryStatus.NO_MATCH,
            publicationTitle = resolved.ref?.label,
            message = resolved.message,
            libraryIntent = resolved.libraryIntent,
        )

    private fun unitAtOrd(
        allUnits: List<PublicationUnitEntity>,
        ord: Int,
    ): PublicationUnitEntity? = allUnits.firstOrNull { ord in it.startOrd..it.endOrd }

    /**
     * Resolve a unidade pedida. Em revista, o índice ("Artigos") é a fonte
     * mais confiável para "artigo 1": os títulos do sumário apontam para a
     * unidade do corpo, não para capa/expediente.
     */
    private suspend fun resolveRequestedUnit(
        target: AttachmentEntity,
        allUnits: List<PublicationUnitEntity>,
        requested: GlobalLibraryUnitRef,
    ): PublicationUnitEntity? {
        if (requested.kind == PublicationUnitKind.ARTICLE && isMagazine(target)) {
            magazineArticleFromToc(target, allUnits, requested)?.let { return it }
        }
        return findUnit(allUnits, requested)
    }

    private suspend fun magazineArticleFromToc(
        target: AttachmentEntity,
        allUnits: List<PublicationUnitEntity>,
        requested: GlobalLibraryUnitRef,
    ): PublicationUnitEntity? {
        val toc = allUnits.firstOrNull {
            it.kind == PublicationUnitKind.ARTICLE.name &&
                normalizeText(it.title) in TOC_TITLES
        } ?: return null
        val wanted = requested.number.toIntOrNull() ?: return null
        if (wanted <= 0) return null
        val rows = units.passages(
            attachmentId = target.id,
            startOrd = toc.startOrd,
            endOrd = toc.endOrd,
            limit = MAX_TOC_SCAN,
        )
        val titles = rows.mapNotNull { row ->
            val match = Regex("""^\d{1,3}\s+(.+)$""").find(row.text.trim())
            match?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 4 }
        }
        val title = titles.getOrNull(wanted - 1) ?: return null
        val normTitle = normalizeText(title)
        return allUnits
            .filter {
                it.kind == PublicationUnitKind.ARTICLE.name &&
                    it.startOrd > toc.endOrd &&
                    normalizeText(it.title).let { unitTitle ->
                        unitTitle == normTitle || unitTitle.startsWith(normTitle)
                    }
            }
            .minByOrNull { it.startOrd }
    }

    private fun findUnit(
        allUnits: List<PublicationUnitEntity>,
        requested: GlobalLibraryUnitRef,
    ): PublicationUnitEntity? {
        val sameKind = allUnits
            .filter { it.kind == requested.kind.name }
            .sortedBy { it.ord }
        sameKind.firstOrNull {
            it.number.equals(requested.number, ignoreCase = true)
        }?.let { return it }
        val ordinal = requested.number.toIntOrNull() ?: return null
        return sameKind.getOrNull(ordinal - 1)
    }

    private fun requestedUnitLabel(requested: GlobalLibraryUnitRef): String {
        val label = when (requested.kind) {
            PublicationUnitKind.PART -> "Parte"
            PublicationUnitKind.CHAPTER -> "Capítulo"
            PublicationUnitKind.ARTICLE -> "Artigo"
            PublicationUnitKind.LESSON -> "Lição"
            PublicationUnitKind.APPENDIX -> "Apêndice"
            PublicationUnitKind.SECTION -> "Seção"
            PublicationUnitKind.DOCUMENT -> "Documento"
        }
        return if (requested.kind == PublicationUnitKind.DOCUMENT) label
        else "$label ${requested.number}"
    }

    private fun isMagazine(attachment: AttachmentEntity): Boolean {
        val symbol = detectSymbol(attachment.fileName).trim().lowercase()
        return Regex("""^(wp?|gn?)(?=[\d\s._-])""").containsMatchIn(symbol) ||
            symbol in setOf("w", "wp", "g", "gn")
    }

    private fun ordinalOf(
        unit: PublicationUnitEntity,
        allUnits: List<PublicationUnitEntity>,
    ): Int? {
        val sameKind = allUnits
            .filter { it.kind == unit.kind }
            .sortedBy { it.ord }
        val index = sameKind.indexOfFirst { it.id == unit.id }
        return if (index >= 0) index + 1 else null
    }

    private fun unitLabel(
        unit: PublicationUnitEntity?,
        ordinal: Int?,
    ): String? {
        if (unit == null) return null
        val kind = runCatching { PublicationUnitKind.valueOf(unit.kind) }
            .getOrElse { PublicationUnitKind.DOCUMENT }
        val label = when (kind) {
            PublicationUnitKind.PART -> "Parte"
            PublicationUnitKind.CHAPTER -> "Capítulo"
            PublicationUnitKind.ARTICLE -> "Artigo"
            PublicationUnitKind.LESSON -> "Lição"
            PublicationUnitKind.APPENDIX -> "Apêndice"
            PublicationUnitKind.SECTION -> "Seção"
            PublicationUnitKind.DOCUMENT -> return "Documento"
        }
        val number = unit.number ?: ordinal?.toString()
        return if (number != null) "$label $number" else label
    }

    private fun sourceTypeOf(attachment: AttachmentEntity): SourceType =
        if (attachment.sourceType.isNotBlank()) {
            SourceType.fromSerial(attachment.sourceType)
        } else {
            SourceType.fromBaseSlot(attachment.baseSlot)
        }

    companion object {
        const val MAX_SOURCES = 4
        private const val MAX_SNIPPET = 320
        private const val MAX_UNIT_SCAN = 600
        private const val MAX_READER_PASSAGES = 80
        private const val MAX_READER_CHARS = 12_000
        private const val MAX_TOC_SCAN = 200
        private val TOC_TITLES = setOf("artigos", "sumario", "conteudo", "indice")

        fun from(db: AppDatabase): GlobalLibraryTool {
            val attachmentDao = db.attachmentDao()
            return GlobalLibraryTool(
                attachments = { attachmentDao.all() },
                retrieval = RoomRetrievalRepository(db.passageDao(), attachmentDao),
                training = RoomTrainingRepository(db.passageDao(), attachmentDao),
                units = RoomGlobalLibraryUnitLookup(db),
            )
        }
    }
}
