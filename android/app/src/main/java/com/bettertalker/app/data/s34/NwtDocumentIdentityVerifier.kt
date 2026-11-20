package com.bettertalker.app.data.s34

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TRUST-2B3B-A — verificador experimental de identidade documental da TNM.
 *
 * Puro: sem UI, Room, LLM ou rede. Não conectado à produção.
 *
 * Verifica somente o que as evidências permitem:
 * - integridade do arquivo contra o SHA-256 registrado;
 * - consistência dos metadados internos do EPUB;
 * - correspondência entre a unidade indexada e o texto extraído;
 * - completude RELATIVA AO PRÓPRIO EPUB.
 *
 * A saída não contém `VERIFIED_LOCAL`. A promoção futura exige uma âncora
 * editorial externa previamente aprovada; OPF/UUID/hash isolados não provam
 * autenticidade oficial.
 */
object NwtDocumentIdentityVerifier {

    private val DOC_ID_REGEX = Regex("""docId-(\d+)""")
    private val CHAPTER_MARK_REGEX = Regex("""<span id="chapter(\d+)"></span>""")
    private val VERSE_MARK_REGEX = Regex("""<span id="chapter(\d+)_verse(\d+)"></span>""")
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun verify(
        metadata: NwtAttachmentMetadata,
        file: File,
        indexedUnit: NwtIndexedUnit? = null,
        expected: NwtExpectedEdition = NwtExpectedEdition(),
        limits: NwtVerifierLimits = NwtVerifierLimits(),
    ): NwtDocumentIdentity {
        val issues = mutableListOf<NwtIdentityIssue>()

        val baseAttachmentConsistent = metadata.attachmentId.isNotBlank() &&
            metadata.kind.equals("epub", ignoreCase = true) &&
            metadata.baseSlot == "nwt" &&
            metadata.indexed &&
            metadata.status == "ready" &&
            (metadata.symbol == null || metadata.symbol == "nwt")

        if (metadata.attachmentId.isBlank()) {
            issues += issue(NwtIssueCode.ATTACHMENT_ID_MISSING, "attachmentId vazio")
        }
        if (!metadata.kind.equals("epub", ignoreCase = true)) {
            issues += issue(NwtIssueCode.ATTACHMENT_KIND_MISMATCH, "kind=${metadata.kind}")
        }
        if (metadata.baseSlot != "nwt") {
            issues += issue(NwtIssueCode.ATTACHMENT_SLOT_MISMATCH, "baseSlot=${metadata.baseSlot}")
        }
        if (metadata.symbol != null && metadata.symbol != "nwt") {
            issues += issue(NwtIssueCode.ATTACHMENT_SYMBOL_MISMATCH, "symbol=${metadata.symbol}")
        }
        if (!metadata.indexed) {
            issues += issue(NwtIssueCode.ATTACHMENT_NOT_INDEXED, "anexo não indexado")
        }
        if (metadata.status != "ready") {
            issues += issue(NwtIssueCode.ATTACHMENT_STATUS_NOT_READY, "status=${metadata.status}")
        }

        if (!file.isFile) {
            issues += issue(NwtIssueCode.FILE_MISSING, "arquivo ausente: ${file.path}")
            return missingResult(
                metadata = metadata,
                indexedUnit = indexedUnit,
                issues = issues,
                attachmentConsistent = baseAttachmentConsistent,
            )
        }

        val fileSize = file.length()
        val sizeConsistent = metadata.sizeBytes <= 0 || fileSize == metadata.sizeBytes
        val attachmentConsistent = baseAttachmentConsistent && sizeConsistent
        if (!sizeConsistent) {
            issues += issue(
                NwtIssueCode.SIZE_MISMATCH,
                "tamanho atual=$fileSize registrado=${metadata.sizeBytes}",
            )
        }

        val actualHash = sha256File(file)
        if (actualHash == null) {
            issues += issue(NwtIssueCode.FILE_UNREADABLE, "não foi possível ler o arquivo")
            return missingResult(
                metadata = metadata,
                indexedUnit = indexedUnit,
                issues = issues,
                integrity = NwtFileIntegrity.FILE_UNREADABLE,
                attachmentConsistent = baseAttachmentConsistent,
            )
        }

        val registeredHash = normalizeHash(metadata.sha256)
        val integrity = when {
            registeredHash == null -> {
                issues += issue(NwtIssueCode.HASH_MISSING, "SHA-256 registrado ausente ou inválido")
                NwtFileIntegrity.HASH_MISSING
            }
            registeredHash != actualHash -> {
                issues += issue(NwtIssueCode.HASH_MISMATCH, "hash atual difere do registrado")
                NwtFileIntegrity.HASH_MISMATCH
            }
            else -> NwtFileIntegrity.HASH_MATCH
        }

        val opened = openZip(file, limits, issues)
        if (opened == null) {
            return NwtDocumentIdentity(
                integrity = if (integrity == NwtFileIntegrity.HASH_MATCH) {
                    NwtFileIntegrity.NOT_EPUB
                } else {
                    integrity
                },
                fileSha256 = actualHash,
                registeredSha256 = registeredHash,
                fileSizeBytes = fileSize,
                attachmentMetadataConsistent = attachmentConsistent,
                epubStructureValid = false,
                editorial = null,
                editorialState = NwtEditorialIdentityState.OPF_MISSING,
                authenticity = NwtAuthenticityAnchorState.NOT_ANCHORED,
                unit = unverifiableUnit(metadata, indexedUnit, issues),
                corpus = NwtCorpusSummary.unknown(issues),
                promotion = NwtPromotionEligibility.blocked(issues),
                issues = issues.distinctIssues(),
            )
        }

        return opened.zip.use { zip ->
            val container = readContainer(zip, opened, limits, issues)
            val opf = container?.let { readOpf(zip, it, opened, limits, issues) }
            val editorial = opf?.let { parseEditorial(it.document) }
            val editorialState = when {
                container == null -> NwtEditorialIdentityState.OPF_MISSING
                opf == null && issues.any { it.code == NwtIssueCode.OPF_MISSING } ->
                    NwtEditorialIdentityState.OPF_MISSING
                opf == null -> NwtEditorialIdentityState.OPF_INVALID
                else -> evaluateEditorial(editorial, expected, issues)
            }

            val spine = opf?.let { parseSpine(it.document, it.path, issues) }
                ?: SpineAnalysis.empty()
            val targetRef = indexedUnit?.canonicalRef?.takeIf { it.isNotBlank() }
            val scan = scanEpub(zip, spine.paths, targetRef, limits, issues)
            val duplicateAmbiguity = opened.duplicateNames.isNotEmpty()
            if (duplicateAmbiguity) {
                issues += issue(
                    NwtIssueCode.DUPLICATE_AMBIGUITY,
                    "ZIP contém ${opened.duplicateNames.size} nome(s) duplicado(s)",
                )
            }
            val structureValid = container != null &&
                opf != null &&
                !duplicateAmbiguity &&
                spine.structurallyConsistent
            val unit = verifyUnit(
                metadata = metadata,
                indexedUnit = indexedUnit,
                scan = scan,
                integrity = integrity,
                structureValid = structureValid,
                issues = issues,
            )
            val corpus = corpusSummary(
                scan = scan,
                spineIssues = spine.issues,
                usedSpine = spine.paths.isNotEmpty(),
                structureValid = structureValid,
                issues = issues,
            )
            val authenticity = evaluateAuthenticity(
                anchor = expected.trustedAnchor,
                identifier = editorial?.identifier,
                fileHash = actualHash,
                issues = issues,
            )

            NwtDocumentIdentity(
                integrity = integrity,
                fileSha256 = actualHash,
                registeredSha256 = registeredHash,
                fileSizeBytes = fileSize,
                attachmentMetadataConsistent = attachmentConsistent,
                epubStructureValid = structureValid,
                editorial = editorial,
                editorialState = editorialState,
                authenticity = authenticity,
                unit = unit,
                corpus = corpus,
                promotion = promotion(
                    integrity = integrity,
                    attachmentConsistent = attachmentConsistent,
                    structureValid = structureValid,
                    editorialState = editorialState,
                    authenticity = authenticity,
                    unit = unit,
                    corpus = corpus,
                    issues = issues,
                ),
                issues = issues.distinctIssues(),
            )
        }
    }

    // ---------- ZIP / XML ----------

    private data class OpenedZip(
        val zip: ZipFile,
        val names: Set<String>,
        val duplicateNames: Set<String>,
    )

    private fun openZip(
        file: File,
        limits: NwtVerifierLimits,
        issues: MutableList<NwtIdentityIssue>,
    ): OpenedZip? {
        val zip = try {
            ZipFile(file)
        } catch (_: ZipException) {
            issues += issue(NwtIssueCode.NOT_EPUB, "ZIP/EPUB inválido")
            return null
        } catch (_: Exception) {
            issues += issue(NwtIssueCode.FILE_UNREADABLE, "não foi possível abrir o ZIP")
            return null
        }

        if (zip.size() > limits.maxEntries) {
            issues += issue(
                NwtIssueCode.ZIP_ENTRY_LIMIT,
                "entradas=${zip.size()} limite=${limits.maxEntries}",
            )
            zip.close()
            return null
        }

        var total = 0L
        val names = linkedSetOf<String>()
        val duplicates = linkedSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!names.add(entry.name)) {
                duplicates += entry.name
                issues += issue(NwtIssueCode.DUPLICATE_ENTRY, "entrada duplicada: ${entry.name}")
            }
            if (entry.size > 0) total += entry.size
            if (entry.size > limits.maxXhtmlBytes && isXhtml(entry.name)) {
                issues += issue(
                    NwtIssueCode.XHTML_TOO_LARGE,
                    "entrada XHTML acima do limite: ${entry.name}",
                )
            }
            if (isUnsafeZipPath(entry.name)) {
                issues += issue(NwtIssueCode.ZIP_PATH_UNSAFE, "caminho suspeito: ${entry.name}")
            }
        }
        if (total > limits.maxTotalUncompressedBytes) {
            issues += issue(
                NwtIssueCode.ZIP_TOO_LARGE,
                "tamanho descomprimido=$total limite=${limits.maxTotalUncompressedBytes}",
            )
            zip.close()
            return null
        }
        return OpenedZip(zip, names, duplicates)
    }

    private data class ContainerRef(val path: String)

    private fun readContainer(
        zip: ZipFile,
        opened: OpenedZip,
        limits: NwtVerifierLimits,
        issues: MutableList<NwtIdentityIssue>,
    ): ContainerRef? {
        if (opened.duplicateNames.contains(CONTAINER_PATH)) {
            issues += issue(NwtIssueCode.DUPLICATE_ENTRY, "container.xml duplicado")
        }
        val bytes = readEntry(
            zip = zip,
            name = CONTAINER_PATH,
            maxBytes = limits.maxXmlBytes,
            missingCode = NwtIssueCode.CONTAINER_MISSING,
            invalidCode = NwtIssueCode.CONTAINER_INVALID,
            issues = issues,
        ) ?: return null
        val doc = parseXml(bytes)
        if (doc == null) {
            issues += issue(NwtIssueCode.CONTAINER_INVALID, "XML inválido em container.xml")
            return null
        }
        val rootfiles = doc.getElementsByTagNameNS("*", "rootfile").toElements()
        val fullPath = rootfiles.firstOrNull {
            it.getAttribute("media-type").contains("oebps-package", ignoreCase = true)
        }?.getAttribute("full-path")
            ?: rootfiles.firstOrNull()?.getAttribute("full-path")
        if (fullPath.isNullOrBlank()) {
            issues += issue(NwtIssueCode.CONTAINER_INVALID, "rootfile/full-path ausente")
            return null
        }
        val safe = sanitizeZipPath(fullPath)
        if (safe == null) {
            issues += issue(NwtIssueCode.OPF_PATH_UNSAFE, "full-path inseguro: $fullPath")
            return null
        }
        return ContainerRef(safe)
    }

    private data class OpfRef(val document: Document, val path: String)

    private fun readOpf(
        zip: ZipFile,
        container: ContainerRef,
        opened: OpenedZip,
        limits: NwtVerifierLimits,
        issues: MutableList<NwtIdentityIssue>,
    ): OpfRef? {
        if (opened.duplicateNames.contains(container.path)) {
            issues += issue(NwtIssueCode.DUPLICATE_ENTRY, "OPF duplicado: ${container.path}")
        }
        val bytes = readEntry(
            zip = zip,
            name = container.path,
            maxBytes = limits.maxXmlBytes,
            missingCode = NwtIssueCode.OPF_MISSING,
            invalidCode = NwtIssueCode.OPF_INVALID,
            issues = issues,
        ) ?: return null
        val doc = parseXml(bytes)
        if (doc == null) {
            issues += issue(NwtIssueCode.OPF_INVALID, "XML inválido no OPF")
            return null
        }
        return OpfRef(doc, container.path)
    }

    // ---------- metadados editoriais ----------

    private fun parseEditorial(doc: Document): NwtEditorialMetadata {
        val uniqueId = doc.documentElement?.getAttribute("unique-identifier").orEmpty()
        val identifiers = doc.getElementsByTagNameNS(DC_NS, "identifier").toElements()
        val chosenIdentifier = identifiers.firstOrNull {
            uniqueId.isNotBlank() && it.getAttribute("id") == uniqueId
        } ?: identifiers.firstOrNull()

        val identifier = chosenIdentifier?.textContent?.trim()?.takeIf { it.isNotBlank() }
        val modified = doc.getElementsByTagNameNS("*", "meta").toElements()
            .firstOrNull {
                it.getAttribute("property").equals("dcterms:modified", ignoreCase = true) ||
                    it.getAttribute("name").equals("dcterms:modified", ignoreCase = true)
            }
            ?.textContent?.trim()?.takeIf { it.isNotBlank() }

        return NwtEditorialMetadata(
            title = firstText(doc, "title"),
            language = firstText(doc, "language"),
            publisher = firstText(doc, "publisher"),
            identifier = identifier,
            modified = modified,
            editionKey = listOfNotNull(identifier, modified)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("|"),
        )
    }

    private fun evaluateEditorial(
        editorial: NwtEditorialMetadata?,
        expected: NwtExpectedEdition,
        issues: MutableList<NwtIdentityIssue>,
    ): NwtEditorialIdentityState {
        if (editorial == null) return NwtEditorialIdentityState.IDENTITY_UNVERIFIABLE

        val title = editorial.title.orEmpty()
        val language = editorial.language.orEmpty()
        val publisher = editorial.publisher.orEmpty()
        val identifier = editorial.identifier.orEmpty()

        if (title.isBlank()) issues += issue(NwtIssueCode.TITLE_MISSING, "dc:title ausente")
        if (language.isBlank()) issues += issue(NwtIssueCode.LANGUAGE_MISSING, "dc:language ausente")
        if (publisher.isBlank()) issues += issue(NwtIssueCode.PUBLISHER_MISSING, "dc:publisher ausente")
        if (identifier.isBlank()) issues += issue(NwtIssueCode.IDENTIFIER_MISSING, "dc:identifier ausente")

        var mismatch = false
        expected.titleContains?.takeIf { it.isNotBlank() }?.let { expectedTitle ->
            if (!normalizeForMatch(title).contains(normalizeForMatch(expectedTitle))) {
                issues += issue(NwtIssueCode.TITLE_MISMATCH, "título não contém o esperado")
                mismatch = true
            }
        }
        expected.language?.takeIf { it.isNotBlank() }?.let { expectedLanguage ->
            if (!normalizeForMatch(language).startsWith(normalizeForMatch(expectedLanguage))) {
                issues += issue(NwtIssueCode.LANGUAGE_MISMATCH, "idioma=$language")
                mismatch = true
            }
        }
        expected.publisherContains?.takeIf { it.isNotBlank() }?.let { expectedPublisher ->
            if (!normalizeForMatch(publisher).contains(normalizeForMatch(expectedPublisher))) {
                issues += issue(NwtIssueCode.PUBLISHER_MISMATCH, "editora não contém a esperada")
                mismatch = true
            }
        }
        expected.identifier?.takeIf { it.isNotBlank() }?.let { expectedIdentifier ->
            if (identifier != expectedIdentifier) {
                issues += issue(NwtIssueCode.IDENTIFIER_MISMATCH, "identificador diferente")
                mismatch = true
            }
        }

        return when {
            mismatch -> NwtEditorialIdentityState.METADATA_INCONSISTENT
            title.isBlank() || language.isBlank() || publisher.isBlank() || identifier.isBlank() ->
                NwtEditorialIdentityState.IDENTITY_UNVERIFIABLE
            else -> NwtEditorialIdentityState.IDENTITY_CONSISTENT
        }
    }

    private fun evaluateAuthenticity(
        anchor: NwtTrustedAnchor?,
        identifier: String?,
        fileHash: String?,
        issues: MutableList<NwtIdentityIssue>,
    ): NwtAuthenticityAnchorState {
        if (anchor == null || (anchor.identifier.isNullOrBlank() && anchor.sha256.isNullOrBlank())) {
            issues += issue(
                NwtIssueCode.NO_TRUSTED_ANCHOR,
                "sem âncora editorial externa previamente aprovada",
            )
            return NwtAuthenticityAnchorState.NOT_ANCHORED
        }
        val identifierOk = anchor.identifier.isNullOrBlank() || anchor.identifier == identifier
        val hashOk = anchor.sha256.isNullOrBlank() ||
            normalizeHash(anchor.sha256) == normalizeHash(fileHash)
        if (!identifierOk) {
            issues += issue(NwtIssueCode.ANCHOR_MISMATCH, "identificador não coincide com a âncora")
        }
        if (!hashOk) {
            issues += issue(NwtIssueCode.ANCHOR_MISMATCH, "hash não coincide com a âncora")
        }
        return if (identifierOk && hashOk) {
            NwtAuthenticityAnchorState.ANCHOR_MATCH
        } else {
            NwtAuthenticityAnchorState.ANCHOR_MISMATCH
        }
    }

    // ---------- unidade / corpus ----------

    private data class VerseKey(val book: Int, val chapter: Int, val verse: Int)

    private data class EpubScan(
        val rawKeys: Set<VerseKey>,
        val parsedKeys: Set<VerseKey>,
        val bookOrders: Set<Int>,
        val chapterKeys: Set<Pair<Int, Int>>,
        val targetText: String?,
        val targetSection: String?,
        val targetFoundCount: Int,
        /** Quantas referências do EPUB compartilham o texto indexado. */
        val textOccurrences: Int,
        val processedPaths: Set<String>,
        val failedPaths: Set<String>,
        val usedSpine: Boolean,
        val issues: List<NwtIdentityIssue>,
    )

    private fun scanEpub(
        zip: ZipFile,
        spinePaths: List<String>,
        targetRef: String?,
        limits: NwtVerifierLimits,
        issues: MutableList<NwtIdentityIssue>,
    ): EpubScan {
        val scanIssues = mutableListOf<NwtIdentityIssue>()
        val paths = spinePaths.ifEmpty { allXhtmlNames(zip) }
        val usedSpine = spinePaths.isNotEmpty()
        if (!usedSpine) {
            scanIssues += issue(NwtIssueCode.SPINE_MISSING, "OPF sem spine utilizável")
        }
        val rawKeys = linkedSetOf<VerseKey>()
        val parsedKeys = linkedSetOf<VerseKey>()
        val bookOrders = linkedSetOf<Int>()
        val chapterKeys = linkedSetOf<Pair<Int, Int>>()
        val processedPaths = linkedSetOf<String>()
        val failedPaths = linkedSetOf<String>()
        val textToRefs = mutableMapOf<String, MutableSet<String>>()
        var targetText: String? = null
        var targetSection: String? = null
        var targetFound = 0
        val normalizedTarget = targetRef?.let(::normalizeRef)

        for (path in paths.distinct()) {
            if (isUnsafeZipPath(path)) {
                scanIssues += issue(NwtIssueCode.ZIP_PATH_UNSAFE, "caminho suspeito: $path")
                failedPaths += path
                continue
            }
            val html = readXhtml(zip, path, limits, scanIssues)
            if (html == null) {
                failedPaths += path
                continue
            }
            if (!isWellFormedXhtml(html)) {
                scanIssues += issue(NwtIssueCode.XHTML_INVALID, "XHTML malformado: $path")
                failedPaths += path
                continue
            }
            val docId = DOC_ID_REGEX.find(html)?.groupValues?.get(1)
            if (docId == null) {
                // Capa/navegação declarada no spine: lida, mas não é livro.
                processedPaths += path
                continue
            }
            val book = NWT_BOOKS[docId]
            if (book == null) {
                scanIssues += issue(NwtIssueCode.PARSER_NO_VERSES, "docId desconhecido: $path")
                failedPaths += path
                continue
            }
            val chapter = CHAPTER_MARK_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
            if (chapter == null) {
                scanIssues += issue(NwtIssueCode.PARSER_NO_VERSES, "capítulo ausente: $path")
                failedPaths += path
                continue
            }
            val marks = VERSE_MARK_REGEX.findAll(html).toList()
            if (marks.isEmpty()) {
                scanIssues += issue(NwtIssueCode.PARSER_NO_VERSES, "sem marcadores: $path")
                failedPaths += path
                continue
            }
            marks.forEach { mark ->
                val verse = mark.groupValues[2].toIntOrNull() ?: return@forEach
                rawKeys += VerseKey(book.order, chapter, verse)
            }
            val parsed = NwtEpubParser.extractFromXhtml(html)
            if (parsed.isNullOrEmpty()) {
                scanIssues += issue(NwtIssueCode.PARSER_NO_VERSES, "parser sem versículos: $path")
                failedPaths += path
                continue
            }
            processedPaths += path
            parsed.forEach { verse ->
                parsedKeys += VerseKey(verse.book.order, verse.chapter, verse.verse)
                bookOrders += verse.book.order
                chapterKeys += verse.book.order to verse.chapter
                val textKey = sha256Text(verse.text)
                textToRefs.getOrPut(textKey) { linkedSetOf() } += verse.canonicalRef
                if (normalizedTarget != null && normalizeRef(verse.canonicalRef) == normalizedTarget) {
                    targetFound++
                    if (targetText == null) {
                        targetText = verse.text
                        targetSection = verse.section
                    }
                }
            }
        }
        if (targetFound > 1) {
            scanIssues += issue(NwtIssueCode.AMBIGUOUS_UNIT_IN_EPUB, "referência repetida no EPUB")
        }
        val textOccurrences = targetText?.let { textToRefs[sha256Text(it)]?.size ?: 1 } ?: 0
        if (textOccurrences > 1) {
            scanIssues += issue(
                NwtIssueCode.AMBIGUOUS_TEXT_IN_EPUB,
                "mesmo texto aparece em $textOccurrences referências",
            )
        }
        if (failedPaths.isNotEmpty()) {
            scanIssues += issue(
                NwtIssueCode.SPINE_RESOURCE_UNPROCESSED,
                "recursos não processados=${failedPaths.size}",
            )
        }
        issues += scanIssues
        return EpubScan(
            rawKeys = rawKeys,
            parsedKeys = parsedKeys,
            bookOrders = bookOrders,
            chapterKeys = chapterKeys,
            targetText = targetText,
            targetSection = targetSection,
            targetFoundCount = targetFound,
            textOccurrences = textOccurrences,
            processedPaths = processedPaths,
            failedPaths = failedPaths,
            usedSpine = usedSpine,
            issues = scanIssues,
        )
    }

    private fun verifyUnit(
        metadata: NwtAttachmentMetadata,
        indexedUnit: NwtIndexedUnit?,
        scan: EpubScan,
        integrity: NwtFileIntegrity,
        structureValid: Boolean,
        issues: MutableList<NwtIdentityIssue>,
    ): NwtUnitIdentity {
        if (!structureValid) {
            issues += issue(NwtIssueCode.UNIT_UNVERIFIABLE_STRUCTURE, "estrutura EPUB inválida")
            return unverifiableUnit(metadata, indexedUnit, issues)
        }
        if (indexedUnit == null) {
            issues += issue(NwtIssueCode.UNIT_NOT_PROVIDED, "nenhuma unidade indexada fornecida")
            return missingUnit(null, issues)
        }
        if (indexedUnit.attachmentId.isBlank() ||
            indexedUnit.attachmentId != metadata.attachmentId
        ) {
            issues += issue(
                NwtIssueCode.ATTACHMENT_MISMATCH,
                "unidade pertence a outro anexo; nenhuma busca global é feita",
            )
            return missingUnit(indexedUnit, issues)
        }
        if (indexedUnit.canonicalRef.isBlank()) {
            issues += issue(NwtIssueCode.UNIT_REF_MISSING, "referência canônica vazia")
            return missingUnit(indexedUnit, issues)
        }
        if (indexedUnit.text.isBlank()) {
            issues += issue(NwtIssueCode.EMPTY_INDEXED_TEXT, "texto indexado vazio")
        }
        val target = scan.targetText
        if (target == null) {
            issues += issue(NwtIssueCode.UNIT_NOT_FOUND_IN_EPUB, "ref ausente no EPUB")
            return missingUnit(indexedUnit, issues)
        }

        val exact = indexedUnit.text == target
        val normalized = normalizeWhitespace(indexedUnit.text) == normalizeWhitespace(target)
        val comparison = when {
            exact -> NwtTextComparison.EXACT
            normalized -> NwtTextComparison.NORMALIZED_WHITESPACE
            else -> NwtTextComparison.MISMATCH
        }
        if (comparison == NwtTextComparison.MISMATCH) {
            issues += issue(NwtIssueCode.UNIT_TEXT_MISMATCH, "texto indexado difere do EPUB")
        }
        val metadataShapeComplete = indexedUnit.passageId?.isNotBlank() == true &&
            indexedUnit.ord != null && indexedUnit.ord >= 0 &&
            !indexedUnit.section.isNullOrBlank()
        if (!metadataShapeComplete) {
            issues += issue(
                NwtIssueCode.UNIT_IDENTITY_INCOMPLETE,
                "passageId/ord/section ausente",
            )
        }
        val sectionMatches = indexedUnit.section
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeForMatch(it) == normalizeForMatch(scan.targetSection.orEmpty()) }
        if (sectionMatches == false) {
            issues += issue(NwtIssueCode.SECTION_MISMATCH, "seção diverge da unidade do EPUB")
        }
        // O verificador puro não consulta o índice persistido: a identidade do
        // registro Room permanece não demonstrada por construção.
        issues += issue(
            NwtIssueCode.INDEX_IDENTITY_UNVERIFIED,
            "passageId/ord/section fornecidos pelo chamador; índice persistido não consultado",
        )
        val state = when {
            indexedUnit.text.isBlank() -> NwtUnitIdentityState.UNIT_PARTIAL
            comparison == NwtTextComparison.MISMATCH -> NwtUnitIdentityState.INDEX_STALE
            !metadataShapeComplete -> NwtUnitIdentityState.UNIT_PARTIAL
            sectionMatches == false -> NwtUnitIdentityState.UNIT_PARTIAL
            scan.targetFoundCount > 1 || scan.textOccurrences > 1 ->
                NwtUnitIdentityState.UNIT_PARTIAL
            integrity == NwtFileIntegrity.HASH_MATCH ->
                NwtUnitIdentityState.UNIT_TEXT_VERIFIED
            else -> NwtUnitIdentityState.UNIT_PARTIAL
        }
        if (integrity == NwtFileIntegrity.HASH_MISMATCH &&
            comparison != NwtTextComparison.MISMATCH
        ) {
            issues += issue(
                NwtIssueCode.FILE_HASH_MISMATCH,
                "texto coincide, mas o arquivo não é o registrado",
            )
        }
        return NwtUnitIdentity(
            state = state,
            attachmentId = indexedUnit.attachmentId,
            passageId = indexedUnit.passageId,
            canonicalRef = indexedUnit.canonicalRef,
            section = indexedUnit.section,
            ord = indexedUnit.ord,
            comparison = comparison,
            textMatchesEpub = comparison != NwtTextComparison.MISMATCH,
            metadataShapeComplete = metadataShapeComplete,
            sectionMatchesEpub = sectionMatches,
            indexIdentityVerified = false,
            indexedChars = indexedUnit.text.length,
            epubChars = target.length,
            indexedTextSha256 = sha256Text(indexedUnit.text),
            epubTextSha256 = sha256Text(target),
            issues = issues.distinctIssues(),
        )
    }

    private fun missingUnit(
        unit: NwtIndexedUnit?,
        issues: List<NwtIdentityIssue>,
    ): NwtUnitIdentity = NwtUnitIdentity(
        state = NwtUnitIdentityState.UNIT_MISSING,
        attachmentId = unit?.attachmentId.orEmpty(),
        passageId = unit?.passageId,
        canonicalRef = unit?.canonicalRef,
        section = unit?.section,
        ord = unit?.ord,
        comparison = NwtTextComparison.NOT_COMPARED,
        textMatchesEpub = false,
        metadataShapeComplete = false,
        sectionMatchesEpub = null,
        indexIdentityVerified = false,
        indexedChars = unit?.text?.length ?: 0,
        epubChars = 0,
        indexedTextSha256 = unit?.text?.let(::sha256Text),
        epubTextSha256 = null,
        issues = issues.distinctIssues(),
    )

    private fun unverifiableUnit(
        metadata: NwtAttachmentMetadata,
        unit: NwtIndexedUnit?,
        issues: List<NwtIdentityIssue>,
    ): NwtUnitIdentity = NwtUnitIdentity(
        state = NwtUnitIdentityState.UNIT_UNVERIFIABLE,
        attachmentId = unit?.attachmentId ?: metadata.attachmentId,
        passageId = unit?.passageId,
        canonicalRef = unit?.canonicalRef,
        section = unit?.section,
        ord = unit?.ord,
        comparison = NwtTextComparison.NOT_COMPARED,
        textMatchesEpub = false,
        metadataShapeComplete = false,
        sectionMatchesEpub = null,
        indexIdentityVerified = false,
        indexedChars = unit?.text?.length ?: 0,
        epubChars = 0,
        indexedTextSha256 = unit?.text?.let(::sha256Text),
        epubTextSha256 = null,
        issues = issues.distinctIssues(),
    )

    private fun corpusSummary(
        scan: EpubScan,
        spineIssues: List<NwtIdentityIssue>,
        usedSpine: Boolean,
        structureValid: Boolean,
        issues: MutableList<NwtIdentityIssue>,
    ): NwtCorpusSummary {
        val missingBooks = (1..66).filter { it !in scan.bookOrders }
        val raw = scan.rawKeys
        val parsed = scan.parsedKeys
        val rawMismatch = raw != parsed
        val corpusIssues = (spineIssues + scan.issues).toMutableList()
        if (missingBooks.isNotEmpty()) {
            val missing = issue(NwtIssueCode.BOOKS_MISSING, "livros ausentes=${missingBooks.size}")
            issues += missing
            corpusIssues += missing
        }
        if (rawMismatch) {
            val mismatch = issue(
                NwtIssueCode.RAW_MARKER_MISMATCH,
                "marcadores=${raw.size} parser=${parsed.size}",
            )
            issues += mismatch
            corpusIssues += mismatch
        }
        val state = when {
            !structureValid || !usedSpine || raw.isEmpty() ->
                NwtCorpusCompleteness.UNKNOWN
            scan.failedPaths.isNotEmpty() -> NwtCorpusCompleteness.PARTIAL
            parsed.any { it !in raw } -> NwtCorpusCompleteness.INCONSISTENT
            missingBooks.isNotEmpty() || rawMismatch -> NwtCorpusCompleteness.PARTIAL
            raw.size == parsed.size -> NwtCorpusCompleteness.COMPLETE_RELATIVE_TO_EPUB
            else -> NwtCorpusCompleteness.PARTIAL
        }
        return NwtCorpusSummary(
            state = state,
            bookDocuments = scan.bookOrders.size,
            chapters = scan.chapterKeys.size,
            parsedVerses = parsed.size,
            rawVerseMarkers = raw.size,
            missingBooks = missingBooks,
            processedResources = scan.processedPaths.size,
            failedResources = scan.failedPaths.size,
            issues = corpusIssues,
        )
    }

    // ---------- promoção ----------

    private fun promotion(
        integrity: NwtFileIntegrity,
        attachmentConsistent: Boolean,
        structureValid: Boolean,
        editorialState: NwtEditorialIdentityState,
        authenticity: NwtAuthenticityAnchorState,
        unit: NwtUnitIdentity,
        corpus: NwtCorpusSummary,
        issues: MutableList<NwtIdentityIssue>,
    ): NwtPromotionEligibility {
        val integrityVerified = integrity == NwtFileIntegrity.HASH_MATCH
        val editorialConsistencyVerified = attachmentConsistent &&
            structureValid &&
            editorialState == NwtEditorialIdentityState.IDENTITY_CONSISTENT
        val unitTextVerified = unit.state == NwtUnitIdentityState.UNIT_TEXT_VERIFIED
        val corpusComplete = corpus.state == NwtCorpusCompleteness.COMPLETE_RELATIVE_TO_EPUB

        // Neste BUILD não existe infraestrutura capaz de demonstrar que a
        // âncora externa é confiável. Correspondência não é autorização.
        issues += issue(
            NwtIssueCode.ANCHOR_PROVENANCE_UNVERIFIED,
            "procedência da âncora externa não demonstrável neste BUILD",
        )
        issues += issue(
            NwtIssueCode.INDEX_IDENTITY_UNVERIFIED,
            "índice persistido não consultado; passageId/ord/section não comprovados",
        )
        if (!integrityVerified) {
            issues += issue(NwtIssueCode.PROMOTION_BLOCKED, "integridade=${integrity.name}")
        }
        if (!attachmentConsistent) {
            issues += issue(NwtIssueCode.PROMOTION_BLOCKED, "metadados do anexo inconsistentes")
        }
        if (!structureValid) {
            issues += issue(NwtIssueCode.PROMOTION_BLOCKED, "estrutura EPUB inválida")
        }
        if (editorialState != NwtEditorialIdentityState.IDENTITY_CONSISTENT) {
            issues += issue(NwtIssueCode.PROMOTION_BLOCKED, "identidade editorial=${editorialState.name}")
        }
        if (authenticity != NwtAuthenticityAnchorState.ANCHOR_MATCH) {
            issues += issue(NwtIssueCode.PROMOTION_BLOCKED, "correspondência da âncora=${authenticity.name}")
        }
        if (!unitTextVerified) {
            issues += issue(NwtIssueCode.PROMOTION_BLOCKED, "unidade=${unit.state.name}")
        }
        if (!corpusComplete) {
            issues += issue(NwtIssueCode.PROMOTION_BLOCKED, "corpus=${corpus.state.name}")
        }

        return NwtPromotionEligibility(
            integrityVerified = integrityVerified,
            editorialConsistencyVerified = editorialConsistencyVerified,
            anchorCorrespondence = authenticity,
            anchorProvenanceTrusted = false,
            unitTextVerified = unitTextVerified,
            unitIndexIdentityVerified = false,
            corpusCompleteRelativeToEpub = corpusComplete,
            canPromoteUnitToLocalFactual = false,
            canPromoteCorpusToLocalFactual = false,
            blockers = issues.distinctIssues(),
        )
    }

    private fun missingResult(
        metadata: NwtAttachmentMetadata,
        indexedUnit: NwtIndexedUnit?,
        issues: MutableList<NwtIdentityIssue>,
        integrity: NwtFileIntegrity = NwtFileIntegrity.FILE_MISSING,
        attachmentConsistent: Boolean = false,
    ): NwtDocumentIdentity = NwtDocumentIdentity(
        integrity = integrity,
        fileSha256 = null,
        registeredSha256 = normalizeHash(metadata.sha256),
        fileSizeBytes = null,
        attachmentMetadataConsistent = attachmentConsistent,
        epubStructureValid = false,
        editorial = null,
        editorialState = NwtEditorialIdentityState.IDENTITY_UNVERIFIABLE,
        authenticity = NwtAuthenticityAnchorState.NOT_ANCHORED,
        unit = unverifiableUnit(metadata, indexedUnit, issues),
        corpus = NwtCorpusSummary.unknown(issues),
        promotion = NwtPromotionEligibility.blocked(issues),
        issues = issues.distinctIssues(),
    )

    // ---------- OPF / spine ----------

    private data class ManifestItem(
        val href: String,
        val mediaType: String,
    )

    private data class SpineAnalysis(
        val paths: List<String>,
        val invalidReferences: List<String>,
        val structurallyConsistent: Boolean,
        val issues: List<NwtIdentityIssue>,
    ) {
        companion object {
            fun empty() = SpineAnalysis(
                paths = emptyList(),
                invalidReferences = emptyList(),
                structurallyConsistent = false,
                issues = emptyList(),
            )
        }
    }

    /**
     * Valida o spine contra o manifesto. Referências inválidas não podem
     * desaparecer: elas tornam a estrutura inconsistente e impedem
     * `COMPLETE_RELATIVE_TO_EPUB`.
     */
    private fun parseSpine(
        doc: Document,
        opfPath: String,
        issues: MutableList<NwtIdentityIssue>,
    ): SpineAnalysis {
        val spineIssues = mutableListOf<NwtIdentityIssue>()
        val items = doc.getElementsByTagNameNS("*", "item").toElements()
        val idCounts = mutableMapOf<String, Int>()
        var manifestInvalid = false
        items.forEach { item ->
            val id = item.getAttribute("id").trim()
            if (id.isBlank()) {
                spineIssues += issue(NwtIssueCode.MANIFEST_ID_MISSING, "item de manifesto sem id")
                manifestInvalid = true
            } else {
                idCounts[id] = (idCounts[id] ?: 0) + 1
            }
        }
        val duplicateIds = idCounts.filterValues { it > 1 }.keys
        duplicateIds.forEach { id ->
            spineIssues += issue(NwtIssueCode.MANIFEST_ID_DUPLICATE, "id duplicado: $id")
            manifestInvalid = true
        }

        val manifest = mutableMapOf<String, ManifestItem>()
        items.forEach { item ->
            val id = item.getAttribute("id").trim()
            if (id.isBlank() || id in duplicateIds) return@forEach
            manifest[id] = ManifestItem(
                href = item.getAttribute("href").trim(),
                mediaType = item.getAttribute("media-type").trim(),
            )
        }

        val paths = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        doc.getElementsByTagNameNS("*", "itemref").toElements().forEach { itemref ->
            val idref = itemref.getAttribute("idref").trim()
            if (idref.isBlank()) {
                spineIssues += issue(NwtIssueCode.SPINE_IDREF_MISSING, "itemref com idref vazio")
                invalid += "<empty>"
                return@forEach
            }
            val item = manifest[idref]
            if (item == null) {
                spineIssues += issue(NwtIssueCode.SPINE_IDREF_UNKNOWN, "idref inexistente: $idref")
                invalid += idref
                return@forEach
            }
            if (item.href.isBlank()) {
                spineIssues += issue(NwtIssueCode.SPINE_HREF_MISSING, "item sem href: $idref")
                invalid += idref
                return@forEach
            }
            val resolved = resolveRelativeZipPath(opfPath, item.href)
            if (resolved == null) {
                spineIssues += issue(NwtIssueCode.OPF_PATH_UNSAFE, "href inseguro: ${item.href}")
                invalid += idref
                return@forEach
            }
            val isContentDocument = (isXhtml(resolved) ||
                item.mediaType.contains("xhtml", ignoreCase = true)) &&
                !isBinaryResource(resolved)
            if (!isContentDocument) {
                spineIssues += issue(
                    NwtIssueCode.SPINE_REFERENCE_INVALID,
                    "itemref não aponta para documento XHTML: $idref",
                )
                invalid += idref
                return@forEach
            }
            paths += resolved
        }
        if (paths.isEmpty()) {
            spineIssues += issue(NwtIssueCode.SPINE_MISSING, "spine sem XHTML")
        }
        issues += spineIssues
        return SpineAnalysis(
            paths = paths.distinct(),
            invalidReferences = invalid.distinct(),
            structurallyConsistent = paths.isNotEmpty() &&
                invalid.isEmpty() &&
                !manifestInvalid,
            issues = spineIssues.distinctIssues(),
        )
    }

    private fun allXhtmlNames(zip: ZipFile): List<String> {
        val out = mutableListOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (isXhtml(entry.name) && !isUnsafeZipPath(entry.name)) out += entry.name
        }
        return out
    }

    // ---------- leitura limitada / XML ----------

    private fun readXhtml(
        zip: ZipFile,
        name: String,
        limits: NwtVerifierLimits,
        issues: MutableList<NwtIdentityIssue>,
    ): String? = readEntry(
        zip = zip,
        name = name,
        maxBytes = limits.maxXhtmlBytes,
        missingCode = NwtIssueCode.XHTML_MISSING,
        invalidCode = NwtIssueCode.XHTML_READ_FAILED,
        issues = issues,
    )?.let { String(it, Charsets.UTF_8) }

    private fun readEntry(
        zip: ZipFile,
        name: String,
        maxBytes: Int,
        missingCode: NwtIssueCode,
        invalidCode: NwtIssueCode,
        issues: MutableList<NwtIdentityIssue>,
    ): ByteArray? {
        val entry: ZipEntry = zip.getEntry(name) ?: run {
            issues += issue(missingCode, "entrada ausente: $name")
            return null
        }
        if (entry.isDirectory) {
            issues += issue(invalidCode, "entrada é diretório: $name")
            return null
        }
        return try {
            zip.getInputStream(entry).use { input ->
                readLimited(input, maxBytes) ?: run {
                    issues += issue(invalidCode, "entrada acima do limite: $name")
                    null
                }
            }
        } catch (_: Exception) {
            issues += issue(invalidCode, "falha ao ler entrada: $name")
            null
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray? {
        if (maxBytes <= 0) return null
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parseXml(bytes: ByteArray, allowDoctype: Boolean = false): Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeatureQuietly(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureQuietly(
                "http://apache.org/xml/features/disallow-doctype-decl",
                !allowDoctype,
            )
            setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureQuietly("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(ByteArrayInputStream(bytes))
    } catch (_: Exception) {
        null
    }

    /**
     * XHTML é XML: validamos sintaxe com o parser seguro. DOCTYPE é aceito para
     * EPUBs comuns, mas entidades internas são rejeitadas e DTDs externos não
     * são carregados. Recursos não bíblicos válidos (capa/navegação) passam.
     */
    private fun isWellFormedXhtml(text: String): Boolean {
        if (text.contains("<!ENTITY", ignoreCase = true)) return false
        val doc = parseXml(text.toByteArray(Charsets.UTF_8), allowDoctype = true)
            ?: return false
        val root = doc.documentElement ?: return false
        return root.localName.equals("html", ignoreCase = true) ||
            root.tagName.equals("html", ignoreCase = true)
    }

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    // ---------- utilitários ----------

    private fun firstText(doc: Document, localName: String): String? =
        doc.getElementsByTagNameNS(DC_NS, localName).toElements()
            .firstOrNull()?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun NodeList.toElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun sha256File(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    private fun sha256Text(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun normalizeHash(value: String?): String? {
        val trimmed = value?.trim()?.lowercase().orEmpty()
        return if (SHA256_REGEX.matches(trimmed)) trimmed else null
    }

    private fun normalizeWhitespace(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("[\\s\\u00A0\\u2007\\u202F]+"), " ")
            .trim()

    private fun normalizeRef(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun normalizeForMatch(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isXhtml(name: String): Boolean =
        name.endsWith(".xhtml", ignoreCase = true) ||
            name.endsWith(".html", ignoreCase = true) ||
            name.endsWith(".htm", ignoreCase = true)

    private fun isBinaryResource(name: String): Boolean {
        val lower = name.lowercase()
        return BINARY_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun isUnsafeZipPath(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.startsWith("/") ||
            normalized.split('/').any { it == ".." } ||
            normalized.contains("//")
    }

    private fun sanitizeZipPath(raw: String): String? {
        val normalized = raw.replace('\\', '/').trim()
        if (normalized.isBlank() || normalized.startsWith("/")) return null
        val parts = normalized.split('/')
        if (parts.any { it == ".." || it == "." || it.isEmpty() }) return null
        return parts.joinToString("/")
    }

    private fun resolveRelativeZipPath(opfPath: String, href: String): String? {
        val cleanHref = href.substringBefore('#').substringBefore('?').trim()
        if (cleanHref.isBlank()) return null
        val base = opfPath.substringBeforeLast('/', "")
        val combined = if (base.isBlank()) cleanHref else "$base/$cleanHref"
        return sanitizeZipPath(combined)
    }

    private fun issue(code: NwtIssueCode, detail: String): NwtIdentityIssue =
        NwtIdentityIssue(code, detail)

    private fun List<NwtIdentityIssue>.distinctIssues(): List<NwtIdentityIssue> =
        distinctBy { it.code to it.detail }

    private val BINARY_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
        ".css", ".js", ".ttf", ".otf", ".woff", ".woff2",
        ".mp3", ".mp4", ".m4a", ".pdf", ".zip",
    )

    private const val CONTAINER_PATH = "META-INF/container.xml"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
}
