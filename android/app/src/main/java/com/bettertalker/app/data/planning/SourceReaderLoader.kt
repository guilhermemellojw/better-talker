package com.bettertalker.app.data.planning

import com.bettertalker.app.data.copilot.refDisplayLabel
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.TextStatus
import com.bettertalker.app.data.util.normalizeText

/** Estado do reader de uma fonte citada. */
enum class SourceReaderStatus {
    READY,
    PARTIAL,
    MISSING,
    INDEXING,
    TEXT_NOT_FOUND,
}

/**
 * 6.0a/P0 — conteúdo do reader de uma fonte da nota.
 *
 * `body` é o texto INTEGRAL da faixa (página + §§, página, capítulo/artigo),
 * carregado do índice. Diferente do prompt, NÃO usa
 * [com.bettertalker.app.data.planning.MAX_UNIT_CHARS].
 */
data class SourceReaderContent(
    val publicationTitle: String,
    val referenceLabel: String,
    val locationLabel: String,
    /** Texto completo da referência (o que está no índice). */
    val body: String,
    /** Trecho que entrou no prompt (pode ser menor que [body]). */
    val usedText: String? = null,
    /** "Interpretação: jr 29 → … — p. 29". */
    val interpretation: String? = null,
    val status: SourceReaderStatus = SourceReaderStatus.READY,
    val reason: String? = null,
    val truncated: Boolean = false,
)

/**
 * Carrega o texto completo de uma citação para o reader.
 *
 * Funções injetadas para manter o loader puro/testável (sem Room).
 */
class SourceReaderLoader(
    private val attachments: suspend () -> List<AttachmentEntity>,
    private val byPageParagraph: suspend (
        attachmentId: String,
        page: Int,
        start: Int,
        end: Int,
    ) -> List<PassageEntity>,
    private val byPage: suspend (
        attachmentId: String,
        start: Int,
        end: Int,
        limit: Int,
    ) -> List<PassageEntity>,
    private val unitSlice: suspend (
        attachmentId: String,
        needleNorm: String,
        rawNeedle: String,
    ) -> List<PassageEntity>?,
) {

    suspend fun load(source: NoteSource): SourceReaderContent {
        val ref = source.refStatus?.ref
        val title = publicationTitle(source)
        val referenceLabel = ref?.let { refDisplayLabel(it) } ?: source.ref
        val location = locationLabel(ref)
        val interpretation = ref?.let {
            "Interpretação: ${source.ref} → ${refDisplayLabel(it)}"
        }
        val usedText = source.refStatus?.promptText?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: source.refStatus?.snippet?.trim()?.takeIf { it.isNotBlank() }

        if (source.kind != SourceKind.PUBLICATION || ref == null) {
            return SourceReaderContent(
                publicationTitle = title,
                referenceLabel = referenceLabel,
                locationLabel = location,
                body = "",
                usedText = usedText,
                interpretation = interpretation,
                status = SourceReaderStatus.MISSING,
                reason = source.hint ?: "Esta fonte não tem leitor de publicação.",
            )
        }

        when (source.citation.edition) {
            EditionStatus.DOWNLOADING,
            EditionStatus.INDEXING,
            -> return SourceReaderContent(
                publicationTitle = title,
                referenceLabel = referenceLabel,
                locationLabel = location,
                body = "",
                usedText = usedText,
                interpretation = interpretation,
                status = SourceReaderStatus.INDEXING,
                reason = source.hint ?: "A edição está sendo baixada/indexada.",
            )

            EditionStatus.MISSING,
            EditionStatus.INDEX_FAILED,
            EditionStatus.UNSUPPORTED,
            -> return SourceReaderContent(
                publicationTitle = title,
                referenceLabel = referenceLabel,
                locationLabel = location,
                body = "",
                usedText = usedText,
                interpretation = interpretation,
                status = SourceReaderStatus.MISSING,
                reason = source.hint ?: "Edição não encontrada no acervo local.",
            )

            EditionStatus.READY -> Unit
        }

        val attachment = findAttachment(source, ref)
        if (attachment == null || !attachment.indexed) {
            return SourceReaderContent(
                publicationTitle = title,
                referenceLabel = referenceLabel,
                locationLabel = location,
                body = "",
                usedText = usedText,
                interpretation = interpretation,
                status = SourceReaderStatus.MISSING,
                reason = "Edição pronta, mas ainda não indexada neste aparelho.",
            )
        }

        val rows = loadRows(attachment.id, ref)
        if (rows.isEmpty()) {
            return SourceReaderContent(
                publicationTitle = title,
                referenceLabel = referenceLabel,
                locationLabel = location,
                body = "",
                usedText = usedText,
                interpretation = interpretation,
                status = SourceReaderStatus.TEXT_NOT_FOUND,
                reason = source.hint
                    ?: "Edição no acervo, mas o trecho citado não foi encontrado.",
            )
        }

        val body = assemble(rows)
        if (body.isBlank()) {
            return SourceReaderContent(
                publicationTitle = title,
                referenceLabel = referenceLabel,
                locationLabel = location,
                body = "",
                usedText = usedText,
                interpretation = interpretation,
                status = SourceReaderStatus.TEXT_NOT_FOUND,
                reason = "O índice não tem texto para esta faixa.",
            )
        }
        val status = if (source.citation.text == TextStatus.PARTIAL) {
            SourceReaderStatus.PARTIAL
        } else {
            SourceReaderStatus.READY
        }
        return SourceReaderContent(
            publicationTitle = title,
            referenceLabel = referenceLabel,
            locationLabel = location,
            body = body,
            usedText = usedText,
            interpretation = interpretation,
            status = status,
        )
    }

    private fun publicationTitle(source: NoteSource): String {
        source.publicationLabel?.takeIf { it.isNotBlank() }?.let { return it }
        source.publicationKey?.let { PubCatalog.titleOf(it) }?.let { return it }
        return source.ref
    }

    private suspend fun findAttachment(
        source: NoteSource,
        ref: RefDetector.DetectedRef,
    ): AttachmentEntity? {
        val all = attachments()
        val byName = source.fileName?.takeIf { it.isNotBlank() }?.let { name ->
            all.firstOrNull { it.fileName == name }
        }
        return byName ?: RefDetector.matchEdition(ref, all)
    }

    private suspend fun loadRows(
        attachmentId: String,
        ref: RefDetector.DetectedRef,
    ): List<PassageEntity> {
        val page = ref.page
        val pageEnd = RefDetector.fullPageEnd(page, ref.pageEnd)
            ?.takeIf { page != null && it > page }
        val start = ref.paragraph
        val end = ref.paragraphEnd?.takeIf { start != null && it > start } ?: start

        val rows = when {
            page != null && start != null -> {
                (page..(pageEnd ?: page)).flatMap { p ->
                    byPageParagraph(attachmentId, p, start, end ?: start)
                }
            }

            page != null -> byPage(
                attachmentId,
                page,
                pageEnd ?: page,
                MAX_READER_PASSAGES,
            )

            !ref.article.isNullOrBlank() || !ref.chapter.isNullOrBlank() -> {
                val raw = ref.article?.takeIf { it.isNotBlank() }
                    ?: ref.chapter.orEmpty()
                val needle = normalizeText(raw)
                if (needle.isBlank()) emptyList()
                else unitSlice(attachmentId, needle, raw).orEmpty()
            }

            else -> emptyList()
        }
        return rows.distinctBy { it.id }
            .sortedWith(compareBy({ it.page ?: 0 }, { it.ord }))
    }

    private fun assemble(rows: List<PassageEntity>): String {
        if (rows.isEmpty()) return ""
        // Com parágrafos §N, preserva a quebra entre parágrafos. Sem §
        // (PDF/EPUB), mantém um bloco contínuo — o splitter já perdeu as
        // fronteiras originais.
        val hasParagraphs = rows.any { it.paragraph != null }
        val body = if (hasParagraphs) {
            rows.groupBy { it.page to it.paragraph }
                .toSortedMap(compareBy({ it.first }, { it.second ?: 0 }))
                .values
                .joinToString("\n\n") { group ->
                    group.joinToString(" ") { it.text.trim() }
                }
        } else {
            rows.joinToString(" ") { it.text.trim() }
        }
        return body.trim().let { text ->
            if (text.length <= MAX_READER_CHARS) text
            else text.take(MAX_READER_CHARS).substringBeforeLast(' ').trim()
        }
    }

    private fun locationLabel(ref: RefDetector.DetectedRef?): String {
        if (ref == null) return ""
        val page = ref.page
        val start = ref.paragraph
        val end = ref.paragraphEnd?.takeIf { start != null && it > start } ?: start
        return buildList {
            if (page != null) {
                val pageEnd = RefDetector.fullPageEnd(page, ref.pageEnd)
                add(if (pageEnd != null && pageEnd > page) "p. $page-$pageEnd" else "p. $page")
            }
            if (start != null) {
                add(if (end != null && end > start) "§§ $start-$end" else "§ $start")
            }
            if (isEmpty()) {
                ref.chapter?.takeIf { it.isNotBlank() }?.let { add(it) }
                ref.article?.takeIf { it.isNotBlank() }?.let { add("“$it”") }
            }
        }.joinToString(" · ")
    }

    companion object {
        /** Teto de segurança para unidades gigantes (capítulo inteiro). */
        const val MAX_READER_PASSAGES = 500

        /** Teto de segurança de caracteres (o sheet usa scroll, sem corte silencioso). */
        const val MAX_READER_CHARS = 200_000
    }
}
