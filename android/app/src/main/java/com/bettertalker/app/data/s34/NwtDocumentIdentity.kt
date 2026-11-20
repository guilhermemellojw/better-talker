package com.bettertalker.app.data.s34

/**
 * TRUST-2B3B-A — modelos do verificador experimental de identidade da TNM.
 *
 * Nenhum destes tipos está conectado a produção. A saída não contém
 * `VERIFIED_LOCAL`: a promoção futura exige uma âncora editorial externa.
 */

enum class NwtFileIntegrity {
    FILE_MISSING,
    FILE_UNREADABLE,
    NOT_EPUB,
    HASH_MISSING,
    HASH_MISMATCH,
    HASH_MATCH,
}

enum class NwtEditorialIdentityState {
    OPF_MISSING,
    OPF_INVALID,
    METADATA_INCONSISTENT,
    IDENTITY_UNVERIFIABLE,
    IDENTITY_CONSISTENT,
}

enum class NwtAuthenticityAnchorState {
    NOT_ANCHORED,
    ANCHOR_MATCH,
    ANCHOR_MISMATCH,
}

enum class NwtUnitIdentityState {
    UNIT_MISSING,
    UNIT_PARTIAL,
    /** O texto coincide com o EPUB; não prova o registro persistido. */
    UNIT_TEXT_VERIFIED,
    INDEX_STALE,
    UNIT_UNVERIFIABLE,
}

enum class NwtTextComparison {
    EXACT,
    NORMALIZED_WHITESPACE,
    MISMATCH,
    NOT_COMPARED,
}

enum class NwtCorpusCompleteness {
    COMPLETE_RELATIVE_TO_EPUB,
    PARTIAL,
    INCONSISTENT,
    UNKNOWN,
}

enum class NwtIssueCode {
    ATTACHMENT_ID_MISSING,
    ATTACHMENT_KIND_MISMATCH,
    ATTACHMENT_SLOT_MISMATCH,
    ATTACHMENT_SYMBOL_MISMATCH,
    ATTACHMENT_NOT_INDEXED,
    ATTACHMENT_STATUS_NOT_READY,
    FILE_MISSING,
    FILE_UNREADABLE,
    NOT_EPUB,
    HASH_MISSING,
    HASH_MISMATCH,
    SIZE_MISMATCH,
    DUPLICATE_ENTRY,
    ZIP_PATH_UNSAFE,
    ZIP_TOO_LARGE,
    ZIP_ENTRY_LIMIT,
    CONTAINER_MISSING,
    CONTAINER_INVALID,
    OPF_MISSING,
    OPF_INVALID,
    OPF_PATH_UNSAFE,
    TITLE_MISSING,
    TITLE_MISMATCH,
    LANGUAGE_MISSING,
    LANGUAGE_MISMATCH,
    PUBLISHER_MISSING,
    PUBLISHER_MISMATCH,
    IDENTIFIER_MISSING,
    IDENTIFIER_MISMATCH,
    NO_TRUSTED_ANCHOR,
    ANCHOR_MISMATCH,
    ANCHOR_PROVENANCE_UNVERIFIED,
    INDEX_IDENTITY_UNVERIFIED,
    SECTION_MISMATCH,
    AMBIGUOUS_TEXT_IN_EPUB,
    DUPLICATE_AMBIGUITY,
    SPINE_RESOURCE_UNPROCESSED,
    SPINE_IDREF_MISSING,
    SPINE_IDREF_UNKNOWN,
    SPINE_HREF_MISSING,
    SPINE_REFERENCE_INVALID,
    SPINE_MISSING,
    XHTML_MISSING,
    XHTML_READ_FAILED,
    XHTML_TOO_LARGE,
    PARSER_NO_VERSES,
    UNIT_NOT_PROVIDED,
    UNIT_REF_MISSING,
    UNIT_NOT_FOUND_IN_EPUB,
    UNIT_UNVERIFIABLE_STRUCTURE,
    UNIT_TEXT_MISMATCH,
    UNIT_IDENTITY_INCOMPLETE,
    EMPTY_INDEXED_TEXT,
    ATTACHMENT_MISMATCH,
    AMBIGUOUS_UNIT_IN_EPUB,
    FILE_HASH_MISMATCH,
    BOOKS_MISSING,
    RAW_MARKER_MISMATCH,
    PROMOTION_BLOCKED,
}

data class NwtIdentityIssue(
    val code: NwtIssueCode,
    val detail: String,
)

data class NwtAttachmentMetadata(
    val attachmentId: String,
    val fileName: String,
    val kind: String,
    val baseSlot: String?,
    val symbol: String?,
    val indexed: Boolean,
    val status: String,
    val sha256: String?,
    val sizeBytes: Long,
    val appPath: String,
)

data class NwtIndexedUnit(
    val attachmentId: String,
    val passageId: String?,
    val canonicalRef: String,
    val ord: Int?,
    val text: String,
    val section: String? = null,
)

/**
 * Perfil de consistência editorial. Não é autoridade oficial: define o que o
 * chamador espera encontrar. [trustedAnchor] serve apenas para correspondência;
 * a confiança na procedência da âncora é uma política externa separada e não
 * demonstrável por este verificador.
 */
data class NwtExpectedEdition(
    val titleContains: String? = "Tradução do Novo Mundo",
    val language: String? = "pt",
    val publisherContains: String? = "Torre de Vigia",
    val identifier: String? = null,
    val trustedAnchor: NwtTrustedAnchor? = null,
)

/**
 * Âncora externa. `ANCHOR_MATCH` significa apenas "coincide com a âncora
 * fornecida"; não demonstra que a âncora é confiável nem que a publicação é
 * oficialmente autêntica. Este BUILD não promove nada a uso factual.
 */
data class NwtTrustedAnchor(
    val identifier: String? = null,
    val sha256: String? = null,
)

data class NwtEditorialMetadata(
    val title: String?,
    val language: String?,
    val publisher: String?,
    val identifier: String?,
    val modified: String?,
    val editionKey: String?,
)

data class NwtUnitIdentity(
    val state: NwtUnitIdentityState,
    val attachmentId: String,
    val passageId: String?,
    val canonicalRef: String?,
    /** Unidade/seção indexada associada à passagem. */
    val section: String?,
    val ord: Int?,
    val comparison: NwtTextComparison,
    /** O texto indexado coincide com o EPUB (fidelidade textual). */
    val textMatchesEpub: Boolean,
    /** passageId, ord e section foram fornecidos com forma completa. */
    val metadataShapeComplete: Boolean,
    /** null = seção não fornecida; false = diverge da seção do EPUB. */
    val sectionMatchesEpub: Boolean?,
    /** Nunca true neste BUILD: exigiria consulta ao índice persistido. */
    val indexIdentityVerified: Boolean,
    val indexedChars: Int,
    val epubChars: Int,
    val indexedTextSha256: String?,
    val epubTextSha256: String?,
    val issues: List<NwtIdentityIssue>,
)

data class NwtCorpusSummary(
    val state: NwtCorpusCompleteness,
    val bookDocuments: Int,
    val chapters: Int,
    val parsedVerses: Int,
    val rawVerseMarkers: Int,
    val missingBooks: List<Int>,
    val processedResources: Int,
    val failedResources: Int,
    val issues: List<NwtIdentityIssue>,
) {
    companion object {
        fun unknown(issues: List<NwtIdentityIssue>) = NwtCorpusSummary(
            state = NwtCorpusCompleteness.UNKNOWN,
            bookDocuments = 0,
            chapters = 0,
            parsedVerses = 0,
            rawVerseMarkers = 0,
            missingBooks = (1..66).toList(),
            processedResources = 0,
            failedResources = 0,
            issues = issues,
        )
    }
}

data class NwtPromotionEligibility(
    val integrityVerified: Boolean,
    val editorialConsistencyVerified: Boolean,
    val anchorCorrespondence: NwtAuthenticityAnchorState,
    /** Sem infraestrutura de confiança externa, permanece false. */
    val anchorProvenanceTrusted: Boolean,
    val unitTextVerified: Boolean,
    /** Sem consulta ao índice persistido, permanece false. */
    val unitIndexIdentityVerified: Boolean,
    val corpusCompleteRelativeToEpub: Boolean,
    /** Bloqueado neste BUILD até existir política externa verificável. */
    val canPromoteUnitToLocalFactual: Boolean,
    val canPromoteCorpusToLocalFactual: Boolean,
    val blockers: List<NwtIdentityIssue>,
) {
    companion object {
        fun blocked(issues: List<NwtIdentityIssue>) = NwtPromotionEligibility(
            integrityVerified = false,
            editorialConsistencyVerified = false,
            anchorCorrespondence = NwtAuthenticityAnchorState.NOT_ANCHORED,
            anchorProvenanceTrusted = false,
            unitTextVerified = false,
            unitIndexIdentityVerified = false,
            corpusCompleteRelativeToEpub = false,
            canPromoteUnitToLocalFactual = false,
            canPromoteCorpusToLocalFactual = false,
            blockers = (issues + listOf(
                NwtIdentityIssue(
                    NwtIssueCode.ANCHOR_PROVENANCE_UNVERIFIED,
                    "procedência da âncora externa não demonstrável neste BUILD",
                ),
                NwtIdentityIssue(
                    NwtIssueCode.INDEX_IDENTITY_UNVERIFIED,
                    "índice persistido não consultado; passageId/ord/section não comprovados",
                ),
            )).distinctBy { it.code to it.detail },
        )
    }
}

data class NwtDocumentIdentity(
    val integrity: NwtFileIntegrity,
    val fileSha256: String?,
    val registeredSha256: String?,
    val fileSizeBytes: Long?,
    val attachmentMetadataConsistent: Boolean,
    val epubStructureValid: Boolean,
    val editorial: NwtEditorialMetadata?,
    val editorialState: NwtEditorialIdentityState,
    val authenticity: NwtAuthenticityAnchorState,
    val unit: NwtUnitIdentity,
    val corpus: NwtCorpusSummary,
    val promotion: NwtPromotionEligibility,
    val issues: List<NwtIdentityIssue>,
)

data class NwtVerifierLimits(
    val maxXmlBytes: Int = 2_000_000,
    val maxXhtmlBytes: Int = 4_000_000,
    val maxEntries: Int = 20_000,
    val maxTotalUncompressedBytes: Long = 300_000_000,
)
