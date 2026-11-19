package com.bettertalker.app.data.publication

import com.bettertalker.app.data.copilot.refDisplayLabel
import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PublicationUnitDao
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.planning.MAX_UNIT_CHARS
import com.bettertalker.app.data.planning.RoomReferenceResolver
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
        RoomReferenceResolver(passageDao, attachmentDao),
) {

    suspend fun resolve(
        occurrence: PublicationReferenceOccurrence,
        expectedAttachmentId: String? = null,
    ): PublicationReferenceResolution {
        val raw = occurrence.raw.trim()
        if (raw.isBlank()) return PublicationReferenceResolution.NotRecognized(occurrence.raw)

        val detected = occurrence.detection ?: redetect(occurrence)
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
                    target = targetOf(occurrence, detected, attachment, resolved),
                )

            resolved.status == ReferenceStatus.PARTIAL && hasText ->
                PublicationReferenceResolution.Partial(
                    target = targetOf(occurrence, detected, attachment, resolved),
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

    private suspend fun targetOf(
        occurrence: PublicationReferenceOccurrence,
        detected: RefDetector.DetectedRef,
        attachment: AttachmentEntity,
        resolved: com.bettertalker.app.domain.planning.ResolvedReference,
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
            unit = unitOf(attachment.id, detected),
            page = detected.page,
            pageEnd = detected.pageEnd,
            paragraph = detected.paragraph,
            paragraphEnd = detected.paragraphEnd,
            sourcePassageId = resolved.passageId,
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
        val needle = listOfNotNull(detected.article, detected.chapter)
            .firstOrNull { it.isNotBlank() }
            ?.let(::normalizeText)
            ?.takeIf { it.length >= 3 }
            ?: return null
        val units = try {
            dao.forAttachment(attachmentId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val matches = units.filter { unit ->
            val title = normalizeText(unit.title)
            val number = unit.number?.let(::normalizeText)
            title.contains(needle) ||
                (number != null && needle.endsWith(number)) ||
                (number != null && needle.contains(" $number"))
        }
        if (matches.size != 1) return null
        return matches.single().toDocumentUnit()
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
     * Agrupa arquivos que podem ser considerados a MESMA edição:
     * - duplicatas binárias (sha256 igual) são equivalentes;
     * - revistas usam o editionKey exato da referência;
     * - livros usam o nome-base do arquivo (sem extensão), que comprova a
     *   mesma distribuição em formatos diferentes. Nomes-base diferentes não
     *   provam equivalência e viram Ambiguous.
     */
    private fun groupEquivalents(
        candidates: List<AttachmentEntity>,
    ): List<List<AttachmentEntity>> {
        val hashes = candidates.mapNotNull { it.sha256?.trim()?.takeIf { value -> value.isNotEmpty() } }
        if (hashes.isNotEmpty() &&
            candidates.all { !it.sha256.isNullOrBlank() } &&
            hashes.distinct().size == 1
        ) {
            return listOf(candidates)
        }
        return candidates.groupBy { attachment ->
            val base = attachment.fileName.substringBeforeLast('.').trim().lowercase()
            if (base.isBlank()) attachment.id else base
        }.values.toList()
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
     * Reconstrução conservadora quando a ocorrência não veio do detector.
     * Só aceita publicação conhecida; não inventa edição de revista.
     */
    private fun redetect(
        occurrence: PublicationReferenceOccurrence,
    ): RefDetector.DetectedRef? {
        RefDetector.detect(occurrence.raw).firstOrNull { detected ->
            occurrence.publicationKey == null ||
                detected.pubKey.equals(occurrence.publicationKey, true)
        }?.let { return it }

        val pubKey = occurrence.publicationKey?.trim()?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val canonical = PubCatalog.resolveSymbol(pubKey) ?: return null
        val kind = if (occurrence.editionKey?.startsWith("book|") == true ||
            PubCatalog.entryOf(canonical)?.kind != "magazine"
        ) {
            RefDetector.Kind.BOOK
        } else {
            RefDetector.Kind.MAGAZINE
        }
        val editionKey = occurrence.editionKey?.takeIf { it.isNotBlank() }
            ?: if (kind == RefDetector.Kind.BOOK) "book|$canonical" else return null
        return RefDetector.DetectedRef(
            raw = occurrence.raw,
            kind = kind,
            pubKey = canonical,
            editionKey = editionKey,
            label = occurrence.titleHint
                ?: PubCatalog.titleOf(canonical)
                ?: occurrence.raw,
            article = occurrence.article,
            chapter = occurrence.chapter,
            page = occurrence.page,
            pageEnd = occurrence.pageEnd,
            paragraph = occurrence.paragraph,
            paragraphEnd = occurrence.paragraphEnd,
        )
    }
}
