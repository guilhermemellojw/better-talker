package com.bettertalker.app.data.publication

import com.bettertalker.app.data.copilot.refDisplayLabel
import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PublicationUnitDao
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.planning.MAX_UNIT_CHARS
import com.bettertalker.app.data.planning.RoomReferenceResolver
import com.bettertalker.app.data.planning.sectionMatchesNeedle
import com.bettertalker.app.data.planning.sectionNeedles
import com.bettertalker.app.data.repo.publicationRefOf
import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.publicationEditionLabel
import com.bettertalker.app.domain.planning.ReferenceStatus
import kotlinx.coroutines.CancellationException

/**
 * UX-REF-1B.1 — resolve a identidade de uma publicação e fixa o anexo antes
 * de delegar a localização da unidade ao [RoomReferenceResolver].
 *
 * Regras de segurança:
 * - coleta candidatos reais, nunca usa apenas o primeiro `matchEdition`;
 * - exige equivalência editorial para preferir formato estruturado;
 * - duas edições/arquivos não comprovadamente equivalentes → Ambiguous;
 * - `expectedAttachmentId` é revalidado; se sumiu/mudou → EditionChanged;
 * - o conteúdo só vira Available/Partial quando o resolvedor devolve texto
 *   real; edição encontrada sem trecho nunca é Available;
 * - `evidenceId`/`usedText` permanecem nulos: recuperar não é usar.
 */
class PublicationReferenceResolver(
    private val attachmentDao: AttachmentDao,
    private val passageDao: PassageDao,
    private val unitDao: PublicationUnitDao? = null,
    private val referenceResolver: RoomReferenceResolver =
        RoomReferenceResolver(passageDao, attachmentDao, unitDao),
) {

    /**
     * @param sourceText texto imutável da mensagem que produziu a ocorrência.
     *   É obrigatório para referências por título com unidade: o resolvedor
     *   valida os offsets literais dentro deste texto. A verificação garante
     *   correspondência literal interna, não autenticidade externa do
     *   chamador; sem ele, a unidade do título é rejeitada.
     */
    suspend fun resolve(
        occurrence: PublicationReferenceOccurrence,
        expectedAttachmentId: String? = null,
        sourceText: String? = null,
    ): PublicationReferenceResolution {
        val raw = occurrence.raw.trim()
        if (raw.isBlank()) return PublicationReferenceResolution.NotRecognized(occurrence.raw)

        val detected = validatedDetection(occurrence, sourceText)
            ?: return PublicationReferenceResolution.NotRecognized(occurrence.raw)
        if (detected.pubKey.isBlank() || detected.pubKey.equals("nwt", true)) {
            return PublicationReferenceResolution.NotRecognized(occurrence.raw)
        }

        val all = try {
            attachmentDao.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PublicationReferenceResolution.ReadFailed(raw, e.message)
        }

        if (detected.ambiguous) {
            val candidates = samePublicationCandidates(detected, all)
                .map { candidateOf(detected, it) }
            return if (candidates.isEmpty()) {
                PublicationReferenceResolution.NotImported(
                    reference = raw,
                    publicationTitle = publicationTitleOf(detected),
                )
            } else {
                PublicationReferenceResolution.Ambiguous(raw, candidates)
            }
        }

        val candidates = all.filter { isExactCandidate(detected, it) }

        if (expectedAttachmentId != null) {
            val pinned = all.firstOrNull { it.id == expectedAttachmentId }
                ?: return PublicationReferenceResolution.EditionChanged(
                    raw,
                    expectedAttachmentId,
                )
            if (candidates.none { it.id == pinned.id }) {
                return PublicationReferenceResolution.EditionChanged(raw, expectedAttachmentId)
            }
            val status = editionStatusOf(pinned)
            if (status != EditionStatus.READY) {
                return PublicationReferenceResolution.NotReady(raw, status, pinned.error)
            }
            return recover(occurrence, detected, pinned)
        }

        if (candidates.isEmpty()) {
            return PublicationReferenceResolution.NotImported(
                reference = raw,
                publicationTitle = publicationTitleOf(detected),
            )
        }

        val groups = groupEquivalents(candidates)
        if (groups.size > 1) {
            return PublicationReferenceResolution.Ambiguous(
                reference = raw,
                candidates = groups.flatMap { group -> group.map { candidateOf(detected, it) } },
            )
        }

        val chosen = choose(groups.single())
        val status = editionStatusOf(chosen)
        if (status != EditionStatus.READY) {
            return PublicationReferenceResolution.NotReady(raw, status, chosen.error)
        }
        return recover(occurrence, detected, chosen)
    }

    private suspend fun recover(
        occurrence: PublicationReferenceOccurrence,
        detected: RefDetector.DetectedRef,
        attachment: AttachmentEntity,
    ): PublicationReferenceResolution {
        // Camada 2 de B4: a busca pode devolver texto de uma unidade vizinha
        // (ex.: Lição 10 para "lição 1"). Quando o índice de unidades existe,
        // exigimos que a unidade solicitada esteja comprovadamente indexada.
        val unit = unitOf(attachment.id, detected)
        if (unitMismatch(attachment.id, detected, unit)) {
            return PublicationReferenceResolution.TextNotFound(
                reference = occurrence.raw,
                publicationTitle = publicationTitleOf(detected) ?: "Publicação",
                editionLabel = editionLabelOf(detected),
            )
        }

        val ref = publicationRefOf(detected)
        val resolved = try {
            referenceResolver.resolvePublication(
                ref = ref,
                expectedAttachmentId = attachment.id,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PublicationReferenceResolution.ReadFailed(occurrence.raw, e.message)
        }

        // `MISSING_CORPUS` no caminho com anexo fixo significa que o arquivo
        // escolhido deixou de existir/valer entre a seleção e a leitura.
        // Nunca é "texto ausente": é a edição que mudou.
        if (resolved.status == ReferenceStatus.MISSING_CORPUS) {
            return PublicationReferenceResolution.EditionChanged(
                reference = occurrence.raw,
                expectedAttachmentId = attachment.id,
            )
        }

        val hasText = !resolved.text.isNullOrBlank()
        return when {
            resolved.status == ReferenceStatus.RESOLVED && hasText ->
                PublicationReferenceResolution.Available(
                    target = targetOf(occurrence, detected, attachment, resolved, unit),
                )

            resolved.status == ReferenceStatus.PARTIAL && hasText ->
                PublicationReferenceResolution.Partial(
                    target = targetOf(occurrence, detected, attachment, resolved, unit),
                    warning = resolved.canonicalRef
                        ?.let { "Trecho localizado por unidade/página/contexto: $it. Confira a edição." }
                        ?: "Trecho localizado de forma parcial; confira a edição.",
                )

            else -> PublicationReferenceResolution.TextNotFound(
                reference = occurrence.raw,
                publicationTitle = publicationTitleOf(detected) ?: "Publicação",
                editionLabel = editionLabelOf(detected),
            )
        }
    }

    private fun targetOf(
        occurrence: PublicationReferenceOccurrence,
        detected: RefDetector.DetectedRef,
        attachment: AttachmentEntity,
        resolved: com.bettertalker.app.domain.planning.ResolvedReference,
        unit: PublicationDocumentUnit?,
    ): PublicationReaderTarget {
        val title = publicationTitleOf(detected) ?: attachment.fileName.substringBeforeLast('.')
        val edition = editionLabelOf(detected)
        return PublicationReaderTarget(
            rawReference = occurrence.raw,
            displayRef = refDisplayLabel(detected),
            canonicalRef = resolved.canonicalRef,
            publicationKey = detected.pubKey.takeIf { it.isNotBlank() },
            publicationTitle = title,
            editionKey = detected.editionKey.takeIf { it.isNotBlank() },
            editionLabel = edition,
            expectedAttachmentId = attachment.id,
            fileName = attachment.fileName,
            sourceLabel = buildString {
                append(title)
                edition?.takeIf { it.isNotBlank() && it != title }?.let {
                    append(" — ").append(it)
                }
            },
            unit = unit,
            page = detected.page,
            pageEnd = detected.pageEnd,
            paragraph = detected.paragraph,
            paragraphEnd = detected.paragraphEnd,
            sourcePassageId = resolved.passageId,
            recoveredText = resolved.text,
            structuralTruncated = wasTruncated(resolved),
            partial = resolved.status == ReferenceStatus.PARTIAL,
            // O resolvedor devolve `text` limitado e, em alguns caminhos,
            // `fullText` integral. Se o texto entregue é menor — ou bateu
            // no teto do resolvedor — o leitor não pode apresentá-lo como
            // conteúdo completo.
            truncated = wasTruncated(resolved),
            // Nesta fase não há vínculo documental comprovado com o turno.
            evidenceId = null,
            usedText = null,
        )
    }

    /** Conservador: no teto do resolvedor já não há prova de texto integral. */
    private fun wasTruncated(
        resolved: com.bettertalker.app.domain.planning.ResolvedReference,
    ): Boolean {
        val text = resolved.text ?: return false
        val full = resolved.fullText
        if (full != null) return full.length > text.length
        // O caminho por unidade (artigo/lição) não preenche `fullText`; o
        // resolvedor usa recorte começo+fim quando passa do teto. O limite
        // de 3 chars vem dessa montagem (metade + " … " + metade).
        return text.length >= MAX_UNIT_CHARS - 3
    }

    private suspend fun unitOf(
        attachmentId: String,
        detected: RefDetector.DetectedRef,
    ): PublicationDocumentUnit? {
        val dao = unitDao ?: return null
        val rawNeedle = listOfNotNull(detected.article, detected.chapter)
            .firstOrNull { it.isNotBlank() }
            ?: return null
        // "cap. 2" e "Capítulo 2" são a mesma unidade indexada.
        val needles = sectionNeedles(rawNeedle)
            .map(::normalizeText)
            .filter { it.length >= 3 }
            .distinct()
        if (needles.isEmpty()) return null
        val requestedNumber = requestedUnitNumber(detected)
        val units = try {
            dao.forAttachment(attachmentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val matches = units.filter { unit ->
            val title = normalizeText(unit.title)
            if (needles.none { sectionMatchesNeedle(title, it) }) return@filter false
            if (requestedNumber == null) return@filter true
            val number = unit.number?.trim()?.toIntOrNull()
                ?: unitNumberFromTitle(title)
            number == requestedNumber
        }
        val canonicalMatches = matches.filter { unit ->
            !unit.canonicalKey.contains("#")
        }
        val selected = when {
            requestedNumber == null -> if (matches.size == 1) matches.single() else null
            canonicalMatches.size == 1 -> canonicalMatches.single()
            canonicalMatches.isEmpty() && matches.size == 1 -> matches.single()
            else -> null
        }
        if (selected == null) return null
        return selected.toDocumentUnit()
    }

    /**
     * Camada 2 de B4: com índice de unidades disponível, uma unidade
     * numerada só é aceita quando existe correspondência exata. Sem índice,
     * a Camada 1 (busca com limites) continua valendo.
     */
    private suspend fun unitMismatch(
        attachmentId: String,
        detected: RefDetector.DetectedRef,
        unit: PublicationDocumentUnit?,
    ): Boolean {
        val requested = requestedUnitNumber(detected) ?: return false
        val dao = unitDao ?: return false
        val indexedCount = try {
            dao.countForAttachment(attachmentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Sem prova de que a unidade indexada é a solicitada.
            return true
        }
        if (indexedCount == 0) return false
        if (unit == null) return true
        val resolved = unit.number?.trim()?.toIntOrNull()
            ?: unitNumberFromTitle(unit.title.orEmpty())
            ?: return true
        return resolved != requested
    }

    private fun requestedUnitNumber(detected: RefDetector.DetectedRef): Int? =
        listOfNotNull(detected.chapter, detected.article)
            .asSequence()
            .mapNotNull { text ->
                UNIT_NUMBER_RE.find(normalizeText(text))
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }
            .firstOrNull()

    private fun unitNumberFromTitle(title: String): Int? =
        UNIT_NUMBER_RE.find(normalizeText(title))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /** Só um SHA-256 hexadecimal de 64 dígitos prova duplicata binária. */
    private fun normalizedSha256(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { SHA256_RE.matches(it) }

    private companion object {
        /**
         * Unidade numerada catalogada: lição, capítulo, estudo, parte, seção
         * ou artigo. O `\b` após o número impede "1" de casar com "10".
         */
        val UNIT_NUMBER_RE = Regex(
            """(?:capitulo|cap|licao|estudo|parte|secao|artigo)\s+(\d{1,3})\b""",
            RegexOption.IGNORE_CASE,
        )
        val SHA256_RE = Regex("^[0-9a-f]{64}$")
    }

    private fun PublicationUnitEntity.toDocumentUnit(): PublicationDocumentUnit? {
        val kind = PublicationUnitKind.entries.firstOrNull {
            it.name.equals(this.kind, ignoreCase = true)
        } ?: return null
        return PublicationDocumentUnit(
            kind = kind,
            number = number,
            title = title.takeIf { it.isNotBlank() && it != "Documento" },
            canonicalKey = canonicalKey.takeIf { it.isNotBlank() },
            startOrd = startOrd,
            endOrd = endOrd,
            startPage = startPage,
            endPage = endPage,
        )
    }

    /**
     * Candidatos exatos. `RefDetector.matchEdition` é o primeiro filtro; a
     * verificação de símbolo/título impede que um `lff` case com `lffi` só
     * por substring (o matcher legado aceita esse atalho para a UI).
     */
    private fun isExactCandidate(
        ref: RefDetector.DetectedRef,
        attachment: AttachmentEntity,
    ): Boolean {
        if (RefDetector.matchEdition(ref, listOf(attachment), includePending = true) == null) {
            return false
        }
        if (ref.kind == RefDetector.Kind.MAGAZINE) {
            // matchEdition já exige código + ano + mês/dia exatos nos nomes
            // oficiais; a edição é a prova de equivalência.
            return true
        }
        return matchesBookSymbols(ref, attachment)
    }

    private fun matchesBookSymbols(
        ref: RefDetector.DetectedRef,
        attachment: AttachmentEntity,
    ): Boolean {
        val keys = publicationKeysOf(ref.pubKey)
        if (keys.isEmpty()) return false
        val slot = canonicalSymbol(attachment.baseSlot)
        val symbol = canonicalSymbol(attachment.symbol)
        if (slot != null && slot in keys) return true
        if (symbol != null && symbol in keys) return true

        val tokens = normalizeText(attachment.fileName).split(' ')
            .filter { it.isNotBlank() }
            .toSet()
        if (tokens.any { it in keys }) return true

        // Arquivo nomeado pelo título, sem sigla: só aceita quando o título
        // identifica UMA única publicação no catálogo. Títulos compartilhados
        // (ex.: "Seja Feliz para Sempre!" em lff e lffi) não provam qual
        // edição é o arquivo e não podem ser escolhidos por semelhança.
        val title = PubCatalog.titleOf(ref.pubKey)?.let(::normalizeText)
        if (!title.isNullOrBlank() &&
            normalizeText(attachment.fileName).contains(title) &&
            titleOwners(title).let { owners ->
                owners.size == 1 && canonicalSymbol(ref.pubKey) in owners
            }
        ) {
            return true
        }
        return false
    }

    /** Símbolos canônicos que usam exatamente este título no catálogo. */
    private fun titleOwners(normalizedTitle: String): Set<String> =
        PubCatalog.titleIndex()
            .filter { it.first == normalizedTitle }
            .mapNotNull { (_, symbol) -> canonicalSymbol(symbol) }
            .toSet()

    private fun publicationKeysOf(pubKey: String): Set<String> {
        val raw = pubKey.trim().lowercase()
        if (raw.isBlank()) return emptySet()
        return buildSet {
            add(raw)
            PubCatalog.resolveSymbol(raw)?.let { add(it) }
            PubCatalog.unifiedOf(raw)?.let { add(it) }
            // Símbolos unificados também aceitam os volumes rotulados.
            if (raw == "it") {
                add("it-1")
                add("it-2")
            }
        }
    }

    private fun canonicalSymbol(raw: String?): String? {
        val clean = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return PubCatalog.resolveSymbol(clean)
            ?: PubCatalog.unifiedOf(clean)
            ?: clean
    }

    /**
     * Candidatos da mesma família para uma referência ambígua (ex.: "g 1/16").
     * Nunca escolhe um deles; serve apenas para explicar a ambiguidade.
     */
    private fun samePublicationCandidates(
        ref: RefDetector.DetectedRef,
        all: List<AttachmentEntity>,
    ): List<AttachmentEntity> {
        val keys = publicationKeysOf(ref.pubKey).toMutableSet()
        // Famílias que compartilham título/editoria.
        when (ref.pubKey.lowercase()) {
            "g", "gn" -> keys += setOf("g", "gn")
            "w", "wp" -> keys += setOf("w", "wp")
        }
        return all.filter { attachment ->
            val slot = canonicalSymbol(attachment.baseSlot)
            val symbol = canonicalSymbol(attachment.symbol)
            val tokens = normalizeText(attachment.fileName).split(' ')
                .filter { it.isNotBlank() }
                .toSet()
            (slot != null && slot in keys) ||
                (symbol != null && symbol in keys) ||
                tokens.any { it in keys } ||
                PubCatalog.titleOf(ref.pubKey)?.let { title ->
                    normalizeText(attachment.fileName).contains(normalizeText(title))
                } == true
        }
    }

    /**
     * Só agrupa arquivos com prova binária: SHA-256 não vazio e idêntico.
     *
     * Nome-base igual NÃO comprova a mesma edição/conteúdo — inclusive entre
     * formatos diferentes. Hash ausente ou diferente deixa cada anexo como
     * candidato próprio; com mais de um grupo o resultado é `Ambiguous`,
     * nunca uma escolha silenciosa.
     */
    private fun groupEquivalents(
        candidates: List<AttachmentEntity>,
    ): List<List<AttachmentEntity>> {
        val byHash = LinkedHashMap<String, MutableList<AttachmentEntity>>()
        val unproven = mutableListOf<List<AttachmentEntity>>()
        candidates.forEach { attachment ->
            val hash = normalizedSha256(attachment.sha256)
            if (hash == null) {
                unproven += listOf(attachment)
            } else {
                byHash.getOrPut(hash) { mutableListOf() } += attachment
            }
        }
        return byHash.values.toList() + unproven
    }

    private fun choose(candidates: List<AttachmentEntity>): AttachmentEntity =
        candidates.sortedWith(
            compareByDescending<AttachmentEntity> { editionStatusOf(it) == EditionStatus.READY }
                .thenByDescending { formatRank(it.fileName) }
                .thenBy { it.id },
        ).first()

    private fun formatRank(fileName: String): Int = when {
        fileName.endsWith(".jwpub", ignoreCase = true) -> 3
        fileName.endsWith(".epub", ignoreCase = true) -> 2
        fileName.endsWith(".pdf", ignoreCase = true) -> 1
        else -> 0
    }

    private fun editionStatusOf(attachment: AttachmentEntity): EditionStatus {
        val kind = detectKind(attachment.fileName)
        return when {
            kind == DocKind.UNSUPPORTED -> EditionStatus.UNSUPPORTED
            attachment.status.equals("downloading", true) -> EditionStatus.DOWNLOADING
            attachment.status.equals("indexing", true) -> EditionStatus.INDEXING
            attachment.status.equals("failed", true) -> EditionStatus.INDEX_FAILED
            attachment.status.equals("missing", true) -> EditionStatus.MISSING
            attachment.indexed && attachment.status.equals("ready", true) -> EditionStatus.READY
            !attachment.indexed -> EditionStatus.INDEXING
            else -> EditionStatus.READY
        }
    }

    private fun candidateOf(
        ref: RefDetector.DetectedRef,
        attachment: AttachmentEntity,
    ): PublicationCandidate = PublicationCandidate(
        attachmentId = attachment.id,
        fileName = attachment.fileName,
        publicationKey = ref.pubKey.takeIf { it.isNotBlank() },
        publicationTitle = publicationTitleOf(ref)
            ?: attachment.fileName.substringBeforeLast('.'),
        editionLabel = editionLabelOf(ref),
        format = detectKind(attachment.fileName).ext,
        indexed = attachment.indexed,
        edition = editionStatusOf(attachment),
    )

    private fun publicationTitleOf(ref: RefDetector.DetectedRef): String? =
        PubCatalog.titleOf(ref.pubKey)?.takeIf { it.isNotBlank() }
            ?: ref.label.substringBefore(" • ").takeIf { it.isNotBlank() }

    private fun editionLabelOf(ref: RefDetector.DetectedRef): String? =
        if (ref.kind == RefDetector.Kind.MAGAZINE) {
            publicationEditionLabel(ref)
        } else {
            null
        }

    /**
     * B2 — a detecção só é aceita se for compatível com o texto original.
     *
     * Para referências normais, o detector roda de novo sobre o `raw` e
     * substitui qualquer unidade fornecida pelo chamador. Para um título por
     * extenso, a unidade só é aceita quando [sourceText] contém literalmente
     * o trecho delimitado por `validationStart`/`validationEndExclusive`, o
     * span visual corresponde ao `raw` e o detector reconhece a unidade
     * nesse trecho. A `detection` fornece apenas a identidade editorial.
     */
    private fun validatedDetection(
        occurrence: PublicationReferenceOccurrence,
        sourceText: String?,
    ): RefDetector.DetectedRef? {
        val detection = occurrence.detection ?: return redetect(occurrence)
        val rawNorm = normalizeText(occurrence.raw)
        val title = detection.pubKey
            .takeIf { it.isNotBlank() }
            ?.let { PubCatalog.titleOf(it)?.let(::normalizeText) }
        val detectionIsTitle = title != null &&
            normalizeText(detection.raw) == title
        val rawIsTitle = title != null && rawNorm == title

        if (detectionIsTitle && rawIsTitle) {
            if (!occurrence.publicationKey.isNullOrBlank() &&
                !detection.pubKey.equals(occurrence.publicationKey, true)
            ) {
                return null
            }
            if (detection.kind == RefDetector.Kind.BOOK &&
                detection.editionKey != "book|${detection.pubKey}"
            ) {
                return null
            }
            val owners = PubCatalog.titleIndex()
                .filter { it.first == title }
                .mapNotNull { (_, symbol) -> canonicalSymbol(symbol) }
                .toSet()
            if (owners.size > 1) return null

            // Sem o texto original não há como provar que a unidade
            // acompanha este título; nunca usar o capítulo/página da
            // detection isoladamente.
            if (sourceText == null) return null
            val start = occurrence.start
            val end = occurrence.endExclusive
            if (start < 0 || end > sourceText.length || end <= start) return null
            if (sourceText.substring(start, end) != occurrence.raw) return null
            val validationStart = occurrence.validationStart ?: return null
            val validationEnd = occurrence.validationEndExclusive ?: return null
            if (validationStart != start ||
                validationEnd > sourceText.length ||
                validationEnd <= end
            ) {
                return null
            }
            val literal = sourceText.substring(validationStart, validationEnd)
            if (!normalizeText(literal).startsWith(rawNorm)) return null
            val validated = RefDetector.detect(literal).firstOrNull {
                it.pubKey.equals(detection.pubKey, true) && it.hasUnit()
            } ?: return null
            if (!occurrenceMatchesValidated(occurrence, validated)) return null
            return validated
        }

        val detected = RefDetector.detect(occurrence.raw).firstOrNull {
            occurrence.publicationKey == null ||
                it.pubKey.equals(occurrence.publicationKey, true)
        }
        if (detected != null) return detected

        // Símbolo isolado (ex.: it-1) pode ter detecção sintética sem
        // unidade. Nunca aceitamos unidade fabricada nesse fallback.
        if (normalizeText(detection.raw) == rawNorm &&
            !detection.hasUnit() &&
            (occurrence.publicationKey.isNullOrBlank() ||
                detection.pubKey.equals(occurrence.publicationKey, true))
        ) {
            return detection
        }
        return null
    }

    /**
     * Metadados explícitos da ocorrência, quando presentes, precisam bater
     * com o que o texto literal comprova. Campos nulos são aceitos: o
     * resolvedor usa a unidade revalidada, não a detecção bruta.
     */
    private fun occurrenceMatchesValidated(
        occurrence: PublicationReferenceOccurrence,
        validated: RefDetector.DetectedRef,
    ): Boolean {
        if (!occurrence.publicationKey.isNullOrBlank() &&
            !occurrence.publicationKey.equals(validated.pubKey, true)
        ) {
            return false
        }
        if (!occurrence.editionKey.isNullOrBlank() &&
            occurrence.editionKey != validated.editionKey
        ) {
            return false
        }
        if (!occurrence.article.isNullOrBlank() &&
            normalizeText(occurrence.article) != normalizeText(validated.article.orEmpty())
        ) {
            return false
        }
        if (!occurrence.chapter.isNullOrBlank() &&
            normalizeText(occurrence.chapter) != normalizeText(validated.chapter.orEmpty())
        ) {
            return false
        }
        if (occurrence.page != null && occurrence.page != validated.page) return false
        if (occurrence.pageEnd != null && occurrence.pageEnd != validated.pageEnd) return false
        if (occurrence.paragraph != null &&
            occurrence.paragraph != validated.paragraph
        ) {
            return false
        }
        if (occurrence.paragraphEnd != null &&
            occurrence.paragraphEnd != validated.paragraphEnd
        ) {
            return false
        }
        return true
    }

    private fun RefDetector.DetectedRef.hasUnit(): Boolean =
        !article.isNullOrBlank() || !chapter.isNullOrBlank() ||
            page != null || pageEnd != null ||
            paragraph != null || paragraphEnd != null

    /**
     * Revalida o texto original. Campos isolados da ocorrência (página,
     * parágrafo, capítulo, artigo) NUNCA são usados sem o detector: eles
     * podem não existir no `raw`. Quando o detector não reconhece o texto,
     * o resultado é `NotRecognized`, nunca uma unidade presumida.
     */
    private fun redetect(
        occurrence: PublicationReferenceOccurrence,
    ): RefDetector.DetectedRef? =
        RefDetector.detect(occurrence.raw).firstOrNull { detected ->
            occurrence.publicationKey == null ||
                detected.pubKey.equals(occurrence.publicationKey, true)
        }
}
