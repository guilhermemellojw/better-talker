package com.bettertalker.app.data.library

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.domain.SourceType
import com.bettertalker.app.data.publication.PublicationUnitKind
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.detectSymbol
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.publicationEditionLabel

/**
 * Resolve a pergunta global em publicação/edição + unidade. Puro/testável.
 *
 * A resolução de edição usa o mesmo [RefDetector] da nota; quando a pergunta
 * não traz uma ref explícita, tenta casar o título do catálogo/nome do anexo.
 * Ambiguidade nunca escolhe uma edição sozinha.
 */
internal object GlobalLibraryResolver {

    internal data class Resolution(
        val status: GlobalLibraryStatus,
        val attachment: AttachmentEntity? = null,
        val candidates: List<AttachmentEntity> = emptyList(),
        val ref: RefDetector.DetectedRef? = null,
        val unit: GlobalLibraryUnitRef? = null,
        val libraryIntent: Boolean = false,
        val message: String? = null,
    )

    private val UNIT_RE = Regex(
        "\\b(artigo|capitulo|licao|apendice|secao|parte|estudo)\\s+" +
            "([0-9]{1,3}|[a-z])\\b"
    )

    fun resolve(question: String, attachments: List<AttachmentEntity>): Resolution {
        val libraryIntent = hasLibraryIntent(question)
        val refs = RefDetector.detect(question)
            .filter { it.kind == RefDetector.Kind.BOOK || it.kind == RefDetector.Kind.MAGAZINE }
            .distinctBy { it.editionKey }

        if (refs.size > 1) {
            val candidates = refs
                .flatMap { samePublicationCandidates(it, attachments) }
                .distinctBy { it.id }
            return Resolution(
                status = GlobalLibraryStatus.AMBIGUOUS,
                candidates = candidates,
                unit = parseUnit(question, refs.first()),
                libraryIntent = true,
                message = "Encontrei mais de uma publicação. Qual delas?",
            )
        }

        if (refs.size == 1) {
            val ref = refs.single()
            if (ref.ambiguous) {
                return Resolution(
                    status = GlobalLibraryStatus.AMBIGUOUS,
                    candidates = samePublicationCandidates(ref, attachments),
                    ref = ref,
                    unit = parseUnit(question, ref),
                    libraryIntent = true,
                    message = "A edição é ambígua. Qual delas?",
                )
            }
            val matches = exactMatches(ref, attachments)
            val chosen = pickAttachment(matches)
            val status = when {
                chosen == null -> GlobalLibraryStatus.MISSING
                isReady(chosen) -> GlobalLibraryStatus.READY
                isPending(chosen) -> GlobalLibraryStatus.INDEXING
                else -> GlobalLibraryStatus.MISSING
            }
            return Resolution(
                status = status,
                attachment = chosen,
                ref = ref,
                unit = parseUnit(question, ref),
                libraryIntent = true,
                message = fallbackMessage(status),
            )
        }

        val titleMatches = attachmentsMatchingTitle(question, attachments)
        if (titleMatches.isEmpty()) {
            return Resolution(
                status = GlobalLibraryStatus.NO_MATCH,
                unit = parseUnit(question, null),
                libraryIntent = libraryIntent,
                message = if (libraryIntent) NO_MATCH_MESSAGE else null,
            )
        }
        if (titleMatches.size > 1) {
            return Resolution(
                status = GlobalLibraryStatus.AMBIGUOUS,
                candidates = titleMatches,
                unit = parseUnit(question, null),
                libraryIntent = true,
                message = "Encontrei mais de uma edição. Qual delas?",
            )
        }
        val chosen = titleMatches.single()
        val status = when {
            isReady(chosen) -> GlobalLibraryStatus.READY
            isPending(chosen) -> GlobalLibraryStatus.INDEXING
            else -> GlobalLibraryStatus.MISSING
        }
        return Resolution(
            status = status,
            attachment = chosen,
            unit = parseUnit(question, null),
            libraryIntent = true,
            message = fallbackMessage(status),
        )
    }

    fun publicationTitle(attachment: AttachmentEntity, ref: RefDetector.DetectedRef? = null): String {
        if (ref != null) {
            publicationEditionLabel(ref)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val symbol = basePubSymbol(detectSymbol(attachment.fileName))
        return PubCatalog.titleOf(symbol)
            ?: attachment.fileName.substringBeforeLast('.').ifBlank { attachment.fileName }
    }

    /**
     * Limpa a pergunta para o retrieval: remove a publicação/edição e a
     * unidade citadas, além dos substantivos de navegação ("livro", "artigo",
     * "diz"). Sem isso, o nome da publicação domina os termos e o hit vem da
     * capa/expediente em vez do conteúdo.
     */
    fun searchQuery(
        question: String,
        ref: RefDetector.DetectedRef?,
        unit: GlobalLibraryUnitRef?,
        publicationTitle: String? = null,
    ): String {
        var q = question
        listOfNotNull(ref?.raw, publicationTitle)
            .filter { it.length >= 3 }
            .sortedByDescending { it.length }
            .forEach { q = q.replace(it, " ", ignoreCase = true) }
        if (unit != null) {
            q = q.replace(
                Regex(
                    "(?i)\\b(artigo|cap[íi]tulo|li[cç][aã]o|ap[êe]ndice|se[cç][aã]o|parte|estudo)" +
                        "\\s+[0-9a-z]+\\b"
                ),
                " ",
            )
        }
        q = q.replace(
            Regex(
                "(?i)\\b(livro|brochura|revista|publica[cç][aã]o|artigo|cap[íi]tulo|" +
                    "li[cç][aã]o|ap[êe]ndice|se[cç][aã]o|parte|estudo|diz|fala|trata)\\b"
            ),
            " ",
        )
        q = q.replace(Regex("\\s+"), " ").trim()
        return q.ifBlank { question }
    }

    fun candidateTitle(attachment: AttachmentEntity): String =
        "${publicationTitle(attachment)} — ${attachment.fileName.substringBeforeLast('.')}"

    fun isTraining(attachment: AttachmentEntity): Boolean {
        if (SourceType.fromSerial(attachment.sourceType) == SourceType.TRAINING) return true
        val slot = attachment.baseSlot?.lowercase()
        if (slot in setOf("be", "th", "s38")) return true
        val symbol = detectSymbol(attachment.fileName).lowercase()
        return symbol in setOf("be", "th", "s38", "s-38")
    }

    fun parseUnit(
        question: String,
        ref: RefDetector.DetectedRef?,
    ): GlobalLibraryUnitRef? =
        parseUnitText(ref?.chapter) ?: parseUnitText(ref?.article) ?: parseUnitText(question)

    private fun parseUnitText(raw: String?): GlobalLibraryUnitRef? {
        if (raw.isNullOrBlank()) return null
        val match = UNIT_RE.find(normalizeText(raw)) ?: return null
        val kind = when (match.groupValues[1]) {
            "artigo", "estudo" -> PublicationUnitKind.ARTICLE
            "capitulo" -> PublicationUnitKind.CHAPTER
            "licao" -> PublicationUnitKind.LESSON
            "apendice" -> PublicationUnitKind.APPENDIX
            "secao" -> PublicationUnitKind.SECTION
            "parte" -> PublicationUnitKind.PART
            else -> return null
        }
        return GlobalLibraryUnitRef(kind, match.groupValues[2].uppercase())
    }

    /** Match exato de edição (inclui placeholders ainda indexando). */
    private fun exactMatches(
        ref: RefDetector.DetectedRef,
        attachments: List<AttachmentEntity>,
    ): List<AttachmentEntity> = attachments.filter { attachment ->
        RefDetector.matchEdition(ref, listOf(attachment), includePending = true) != null
    }

    /**
     * Candidatos da MESMA publicação para o caso ambíguo — não usa fallback
     * de chave em edição exata, senão uma Sentinela de julho poderia virar
     * junho silenciosamente.
     */
    private fun samePublicationCandidates(
        ref: RefDetector.DetectedRef,
        attachments: List<AttachmentEntity>,
    ): List<AttachmentEntity> {
        val keys = setOfNotNull(
            PubCatalog.resolveSymbol(ref.pubKey),
            PubCatalog.unifiedOf(ref.pubKey),
            basePubSymbol(ref.pubKey),
        )
        return attachments.filter { attachment ->
            keys.any { key ->
                attachment.baseSlot.equals(key, ignoreCase = true) ||
                    attachment.symbol.equals(key, ignoreCase = true) ||
                    basePubSymbol(detectSymbol(attachment.fileName))
                        .equals(key, ignoreCase = true)
            }
        }
    }

    private fun attachmentsMatchingTitle(
        question: String,
        attachments: List<AttachmentEntity>,
    ): List<AttachmentEntity> {
        val normQuestion = normalizeText(question)
        if (normQuestion.isBlank()) return emptyList()
        return attachments.filter { attachment ->
            publicationTitles(attachment).any { title ->
                val normTitle = normalizeText(title)
                normTitle.length >= 4 && normQuestion.contains(normTitle)
            }
        }
    }

    private fun publicationTitles(attachment: AttachmentEntity): List<String> {
        val symbol = detectSymbol(attachment.fileName)
        val base = basePubSymbol(symbol)
        return buildList {
            PubCatalog.titleOf(base)?.let { add(it) }
            PubCatalog.titleOf(symbol)?.let { add(it) }
            attachment.baseSlot?.let { PubCatalog.titleOf(it) }?.let { add(it) }
            attachment.symbol?.let { PubCatalog.titleOf(it) }?.let { add(it) }
        }
            .flatMap { listOf(it, it.substringBefore(" (")) }
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
    }

    private fun basePubSymbol(symbol: String): String {
        val clean = symbol.trim().lowercase()
        PubCatalog.resolveSymbol(clean)?.let { return it }
        return Regex("^(wp?|gn?|mwb)").find(clean)?.value ?: clean
    }

    private fun pickAttachment(matches: List<AttachmentEntity>): AttachmentEntity? =
        matches.firstOrNull(::isReady)
            ?: matches.firstOrNull(::isPending)
            ?: matches.firstOrNull()

    private fun isReady(attachment: AttachmentEntity): Boolean =
        attachment.indexed && attachment.status.equals("ready", ignoreCase = true)

    private fun isPending(attachment: AttachmentEntity): Boolean =
        !isReady(attachment) &&
            (attachment.status.equals("indexing", ignoreCase = true) ||
                attachment.status.equals("downloading", ignoreCase = true) ||
                !attachment.indexed)

    private fun fallbackMessage(status: GlobalLibraryStatus): String? = when (status) {
        GlobalLibraryStatus.MISSING -> MISSING_MESSAGE
        GlobalLibraryStatus.INDEXING -> INDEXING_MESSAGE
        else -> null
    }

    private fun hasLibraryIntent(question: String): Boolean {
        val norm = normalizeText(question)
        return listOf(
            "livro", "brochura", "revista", "sentinela", "despertai",
            "publicacao", "artigo", "capitulo", "licao", "apendice",
            "acervo", "biblioteca", "fonte",
        ).any { norm.contains(it) }
    }

    const val MISSING_MESSAGE =
        "Não está no acervo; abra a Biblioteca para baixar/importar."
    const val INDEXING_MESSAGE =
        "Está no acervo, mas ainda está indexando."
    const val NO_MATCH_MESSAGE =
        "Não encontrei no texto indexado; posso procurar em outra publicação."
}
