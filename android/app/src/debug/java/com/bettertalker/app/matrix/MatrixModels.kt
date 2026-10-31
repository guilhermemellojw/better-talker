package com.bettertalker.app.matrix

/** Linha sanitizada da matriz C2.6 (sem texto dos DOCX). */
data class MatrixRow(
    val file: String,
    val raw: String,
    val edition: String,
    val page: Int?,
    val pageEnd: Int?,
    val paragraph: Int?,
    val paragraphEnd: Int?,
    val ambiguous: Boolean,
)

/** Plano de uma edição: formatos e URLs descobertos na API oficial. */
data class EditionPlan(
    val edition: String,
    val pub: String,
    val issue: String?,
    val status: String,
    val formats: Map<String, String> = emptyMap(),
    val fileNames: Map<String, String> = emptyMap(),
    val sizes: Map<String, Long> = emptyMap(),
    val pubName: String = "",
    val formattedDate: String = "",
    val error: String? = null,
) {
    val preferredFormat: String?
        get() = FORMAT_PREFERENCE.firstOrNull { formats.containsKey(it) }

    val preferredUrl: String? get() = preferredFormat?.let { formats[it] }
    val preferredFileName: String? get() = preferredFormat?.let { fileNames[it] }
    val preferredSize: Long get() = preferredFormat?.let { sizes[it] } ?: 0L

    companion object {
        /** Ordem do app: texto estruturado primeiro. */
        val FORMAT_PREFERENCE = listOf("JWPUB", "EPUB", "PDF", "RTF")
    }
}

/** Resultado do AcervoBuilder para uma edição. */
data class EditionResult(
    val edition: String,
    val status: String,
    val format: String? = null,
    val fileName: String? = null,
    val expectedFileName: String? = null,
    val attachmentId: String? = null,
    val bytes: Long = 0L,
    val error: String? = null,
)

/** Resultado consolidado do AcervoBuilder. */
data class AcervoReport(
    val results: List<EditionResult>,
    val totalBytes: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val formatValidation: List<EditionResult> = emptyList(),
) {
    val byEdition: Map<String, EditionResult> = results.associateBy { it.edition }
    val ready: Int get() = results.count { it.status == "READY" }
    val failed: Int get() = results.count { it.status.startsWith("FAILED") || it.status == "INDEX_FAILED" }
    val unavailable: Int get() = results.count { it.status == "UNAVAILABLE" || it.status == "PAGE_ONLY" }
}

/** Resultado da matriz por referência. */
data class MatrixResult(
    val row: MatrixRow,
    val resolverStatus: String,
    val resolverCanonical: String?,
    val resolverChars: Int,
    val citedOk: Boolean,
    val citedDetail: String?,
    val status: String,
    val reason: String,
    val action: String,
    val format: String?,
    val edition: String,
    val fileName: String?,
    val expectedFileName: String?,
    val wrongEdition: Boolean,
    val noteSourceFound: Boolean,
)

data class MatrixReport(
    val results: List<MatrixResult>,
    val startedAt: Long,
    val finishedAt: Long,
) {
    val total: Int get() = results.size
    val resolved: Int get() = results.count { it.status == "RESOLVED" }
    val partial: Int get() = results.count { it.status == "PARTIAL" }
    val textNotFound: Int get() = results.count { it.status == "TEXT_NOT_FOUND" }
    val editionMissing: Int get() = results.count { it.status == "EDITION_MISSING" }
    val other: Int get() = total - resolved - partial - textNotFound - editionMissing
    val usable: Int get() = resolved + partial
}
