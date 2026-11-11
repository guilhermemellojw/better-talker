package com.bettertalker.app.data.library

import com.bettertalker.app.data.publication.PublicationUnitKind

/** Estado explícito de uma consulta à Biblioteca no assistente global. */
enum class GlobalLibraryStatus {
    READY,
    INDEXING,
    MISSING,
    AMBIGUOUS,
    NO_MATCH,
    TRAINING_ONLY,
}

/** Fonte citável devolvida pelo [GlobalLibraryTool]. */
data class GlobalLibrarySource(
    /** 1-based; o prompt/resposta usam [Fonte N]. */
    val index: Int,
    val attachmentId: String,
    val publicationTitle: String,
    val unitLabel: String?,
    val unitTitle: String?,
    val page: Int?,
    val pageEnd: Int?,
    val paragraph: Int?,
    val snippet: String,
    val startOrd: Int,
    val endOrd: Int,
    val unitId: String? = null,
    val training: Boolean = false,
) {
    val pageLabel: String?
        get() = when {
            page == null -> null
            pageEnd != null && pageEnd != page -> "$page-$pageEnd"
            else -> page.toString()
        }

    val locationLabel: String
        get() = buildList {
            pageLabel?.let { add("pág. $it") }
            paragraph?.let { add("§ $it") }
        }.joinToString(" · ")
}

/** Edição/publicação candidata quando a pergunta é ambígua. */
data class GlobalLibraryCandidate(
    val attachmentId: String,
    val title: String,
)

data class GlobalLibraryResult(
    val status: GlobalLibraryStatus,
    val sources: List<GlobalLibrarySource> = emptyList(),
    val publicationId: String? = null,
    val publicationTitle: String? = null,
    val candidates: List<GlobalLibraryCandidate> = emptyList(),
    /** Texto local de fallback (MISSING/INDEXING/AMBIGUOUS/NO_MATCH). */
    val message: String? = null,
    val searchedElsewhere: Boolean = false,
    val trainingOnly: Boolean = false,
    val libraryIntent: Boolean = false,
)

/** Unidade pedida em linguagem natural ("artigo 1", "capítulo 2"). */
data class GlobalLibraryUnitRef(
    val kind: PublicationUnitKind,
    val number: String,
)

data class GlobalLibraryReaderContent(
    val publicationTitle: String,
    val unitLabel: String?,
    val unitTitle: String?,
    val locationLabel: String,
    val body: String,
    val training: Boolean,
    val truncated: Boolean,
)
