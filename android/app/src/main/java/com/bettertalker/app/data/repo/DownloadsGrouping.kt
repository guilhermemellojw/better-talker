package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.detectSymbol

/**
 * Grupo de arquivos de uma mesma publicação/edição encontrados em Downloads.
 * O melhor formato é escolhido por JWPUB > EPUB > PDF; os demais são
 * duplicatas do grupo (a UI mostra quantas).
 */
data class DownloadGroup(
    val key: String,
    val symbol: String,
    val candidates: List<DownloadCandidate>,
    val best: DownloadCandidate,
    val alreadyInLibrary: Boolean,
) {
    val duplicateCount: Int get() = (candidates.size - 1).coerceAtLeast(0)
    val formats: String
        get() = candidates.map { it.name.substringAfterLast('.', "").uppercase() }
            .distinct().joinToString(" + ")
}

/** Rank de formato: JWPUB > EPUB > PDF > outros. */
fun downloadFormatRank(name: String): Int = when (detectKind(name)) {
    DocKind.JWPUB -> 4
    DocKind.EPUB -> 3
    DocKind.PDF -> 2
    DocKind.DOCX -> 1
    DocKind.RTF -> 1
    DocKind.ZIP -> 1
    DocKind.TXT -> 1
    DocKind.UNSUPPORTED -> 0
}

/**
 * Agrupa candidatos por símbolo editorial. Puro/testável.
 *
 * - já registrado por nome ou símbolo → `alreadyInLibrary`;
 * - melhor formato do grupo em `best`;
 * - grupos ordenados por símbolo.
 */
fun groupDownloadCandidates(
    candidates: List<DownloadCandidate>,
    registeredNames: Set<String>,
    library: List<AttachmentEntity>,
): List<DownloadGroup> {
    if (candidates.isEmpty()) return emptyList()
    val librarySymbols = library.map { it.symbol ?: detectSymbol(it.fileName) }.toSet()
    return candidates
        .distinctBy { it.uriString }
        .groupBy { detectSymbol(it.name) }
        .map { (symbol, items) ->
            val best = items.maxWithOrNull(
                compareBy<DownloadCandidate> { downloadFormatRank(it.name) }
                    .thenBy { it.sizeBytes },
            ) ?: items.first()
            DownloadGroup(
                key = symbol,
                symbol = symbol,
                candidates = items.sortedByDescending { downloadFormatRank(it.name) },
                best = best,
                alreadyInLibrary = best.name in registeredNames || symbol in librarySymbols,
            )
        }
        .sortedBy { it.symbol }
}
