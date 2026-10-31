package com.bettertalker.app.matrix

import android.content.Context
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.planning.RoomReferenceResolver
import com.bettertalker.app.data.repo.CopilotRepository
import com.bettertalker.app.data.repo.publicationRefOf
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.NoteRefs
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.SourceAction
import com.bettertalker.app.data.util.TextStatus
import com.bettertalker.app.data.util.citationReason
import com.bettertalker.app.data.util.RefDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Roda as 391 refs da matriz pelo MESMO pipeline do app:
 * RoomReferenceResolver (texto exato) + citedSources (evidência do Copilot)
 * + NoteSources (checklist/edição/texto/ação).
 */
class MatrixRunner(
    private val ctx: Context,
    private val db: AppDatabase,
    private val log: (String) -> Unit,
) {
    suspend fun run(rows: List<MatrixRow>, acervo: AcervoReport): MatrixReport {
        val started = System.currentTimeMillis()
        val copilot = CopilotRepository(db)
        val resolver = RoomReferenceResolver(db.passageDao(), db.attachmentDao())
        val detected = rows.map { detectedFor(it) }
        log("MATRIZ refs=${rows.size} edições=${acervo.results.size}")
        val noteSources = copilot.noteSources(
            noteId = "matrix-c26",
            outlineRefsJson = "",
            noteText = "",
            noteTitle = "MATRIZ C2.6",
            refs = NoteRefs(detected, emptyList()),
        )
        log("MATRIZ NoteSources=${noteSources.total} resolvidas=${noteSources.resolved}")
        val sources = noteSources.sources

        val results = mutableListOf<MatrixResult>()
        rows.forEachIndexed { i, row ->
            val ref = detected[i]
            val resolved = withContext(Dispatchers.IO) {
                resolver.resolvePublication(publicationRefOf(ref))
            }
            val cited = withContext(Dispatchers.IO) {
                copilot.citedSources(listOf(ref))
            }
            val source = findSource(sources, row, ref)
            val acervoResult = acervo.byEdition[row.edition]
            val expected = acervoResult?.expectedFileName
            val actual = source?.fileName ?: acervoResult?.fileName
            val wrongEdition = expected != null && actual != null && actual != expected
            val format = actual?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotBlank() }
                ?: acervoResult?.format
            val status = if (wrongEdition) "WRONG_EDITION" else statusOf(source, resolved.status)
            val reason = when {
                wrongEdition -> "matcher usou $actual; esperado $expected"
                source != null -> citationReason(source)
                resolved.text != null -> "resolver ${resolved.status.name.lowercase()} (${resolved.text.length} chars)"
                else -> "resolver ${resolved.status.name.lowercase()}"
            }
            val action = if (wrongEdition) "FIX_MATCHER" else source?.citation?.action?.name
                ?: when (resolved.status.name) {
                    "RESOLVED", "PARTIAL" -> SourceAction.NONE.name
                    "MISSING_CORPUS" -> SourceAction.DOWNLOAD.name
                    else -> SourceAction.REINDEX.name
                }
            results += MatrixResult(
                row = row,
                resolverStatus = resolved.status.name,
                resolverCanonical = resolved.canonicalRef,
                resolverChars = resolved.text?.length ?: 0,
                citedOk = cited.sources.isNotEmpty(),
                citedDetail = cited.unavailableDetails.firstOrNull(),
                status = status,
                reason = reason,
                action = action,
                format = format,
                edition = row.edition,
                fileName = actual,
                expectedFileName = expected,
                wrongEdition = wrongEdition,
                noteSourceFound = source != null,
            )
            if ((i + 1) % 25 == 0 || i == rows.lastIndex) {
                log("MATRIZ ${i + 1}/${rows.size} último=${row.raw} → $status")
            }
        }
        return MatrixReport(results, started, System.currentTimeMillis())
    }

    private fun findSource(
        sources: List<NoteSource>,
        row: MatrixRow,
        ref: RefDetector.DetectedRef,
    ): NoteSource? =
        sources.firstOrNull { it.ref == row.raw && it.editionKey == row.edition }
            ?: sources.firstOrNull { it.ref == row.raw }
            ?: sources.firstOrNull { it.editionKey == row.edition }
            ?: sources.firstOrNull {
                it.publicationKey == ref.pubKey &&
                    it.chapter == null && it.verse == null
            }

    private fun statusOf(source: NoteSource?, resolver: com.bettertalker.app.domain.planning.ReferenceStatus): String {
        if (source != null) {
            val c = source.citation
            if (c.text == TextStatus.AMBIGUOUS) return "AMBIGUOUS"
            return when (c.edition) {
                EditionStatus.READY -> when (c.text) {
                    TextStatus.RESOLVED -> "RESOLVED"
                    TextStatus.PARTIAL -> "PARTIAL"
                    TextStatus.NOT_FOUND -> "TEXT_NOT_FOUND"
                    TextStatus.AMBIGUOUS -> "AMBIGUOUS"
                }
                EditionStatus.MISSING -> "EDITION_MISSING"
                EditionStatus.DOWNLOADING -> "DOWNLOADING"
                EditionStatus.INDEXING -> "INDEXING"
                EditionStatus.INDEX_FAILED -> "INDEX_FAILED"
                EditionStatus.UNSUPPORTED -> "UNSUPPORTED"
            }
        }
        return when (resolver.name) {
            "RESOLVED" -> "RESOLVED"
            "PARTIAL" -> "PARTIAL"
            "MISSING_CORPUS" -> "EDITION_MISSING"
            else -> "UNRESOLVED"
        }
    }

    private fun detectedFor(row: MatrixRow): RefDetector.DetectedRef {
        val book = row.edition.startsWith("book|")
        val pubKey = if (book) {
            val p = row.edition.split("|")
            val raw = p.getOrNull(1).orEmpty()
            if (raw.startsWith("it")) "it" else raw
        } else {
            row.edition.substringBefore('|')
        }
        return RefDetector.DetectedRef(
            raw = row.raw,
            kind = if (book) RefDetector.Kind.BOOK else RefDetector.Kind.MAGAZINE,
            pubKey = pubKey,
            editionKey = row.edition,
            label = row.raw,
            page = row.page,
            pageEnd = row.pageEnd,
            paragraph = row.paragraph,
            paragraphEnd = row.paragraphEnd,
            ambiguous = row.ambiguous,
            context = row.raw,
        )
    }
}
