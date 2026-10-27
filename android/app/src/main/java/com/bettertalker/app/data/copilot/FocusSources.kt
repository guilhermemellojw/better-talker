package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.EvidenceSource
import com.bettertalker.app.data.util.NoteRefs
import com.bettertalker.app.data.util.NoteRefsCollector
import com.bettertalker.app.data.util.NoteSource
import com.bettertalker.app.data.util.NoteSources
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.SourceKind
import com.bettertalker.app.data.util.SourceStatus
import com.bettertalker.app.data.util.normalizeText

/**
 * C2 — fontes do PONTO ATUAL.
 *
 * O bloco é montado a partir das refs da nota, não de uma varredura nova:
 * - prioridade: subponto → seção → pack (até 8) → pergunta;
 * - o mesmo `sourceKey` do checklist/inventário deduplica as trilhas;
 * - texto que já está no dossiê/pinned não é repetido no bloco;
 * - toda ref entra no [FocusSources.entries], inclusive as omitidas, com
 *   unidade, status, texto e motivo.
 *
 * Puro/testável: sem Android, sem IO.
 */

/** Ordem de prioridade: menor = mais específico. */
enum class FocusOrigin { SUBPOINT, SECTION, PACK, QUESTION, NOTE }

/** Onde a fonte foi usada neste turno. */
enum class FocusDestination {
    /** Texto literal dentro de `## FONTES DO PONTO ATUAL`. */
    FOCUS_BLOCK,
    /** Texto já vem no dossiê da seção; o bloco só referencia. */
    DOSSIER,
    /** Texto já vem no pack RAG; o bloco só referencia. */
    PACK,
    /** Texto já vem no bloco volátil da pergunta. */
    QUESTION,
    /** Só metadado no inventário da nota (não é fonte deste ponto). */
    INVENTORY,
    /** Fora do prompt; [FocusSource.omissionReason] explica. */
    OMITTED,
}

enum class FocusStatus {
    RESOLVED,
    PARTIAL,
    TEXT_NOT_FOUND,
    EDITION_MISSING,
    DOWNLOADING,
    INDEXING,
    INDEX_FAILED,
    UNSUPPORTED,
}

data class FocusSource(
    val key: String,
    val reference: String,
    val kind: SourceKind,
    val origin: FocusOrigin,
    val status: FocusStatus,
    val unit: String? = null,
    /** Texto integral resolvido (pode ser maior que o usado no bloco). */
    val text: String = "",
    /** Tamanho do texto original antes do recorte do bloco. */
    val originalChars: Int = text.length,
    /** Tamanho do texto efetivamente enviado no bloco. */
    val truncatedChars: Int = 0,
    val destination: FocusDestination = FocusDestination.INVENTORY,
    val omissionReason: String? = null,
    /** Rótulo humano da publicação/edição, quando existir. */
    val publicationLabel: String? = null,
    /** Chave de agrupamento ("w", "lmd", "th", "be"). */
    val publicationKey: String? = null,
) {
    /** Texto que o prompt realmente recebe neste bloco. */
    val usedText: String
        get() = if (destination == FocusDestination.FOCUS_BLOCK) text else ""

    val isTruncated: Boolean
        get() = destination == FocusDestination.FOCUS_BLOCK && truncatedChars < originalChars
}

data class FocusSources(
    val entries: List<FocusSource>,
    val block: String,
    val maxChars: Int = MAX_FOCUS_BLOCK_CHARS,
) {
    val promptSources: List<FocusSource>
        get() = entries.filter { it.destination == FocusDestination.FOCUS_BLOCK }
    /** Refs do ponto atual (subponto/seção), inclusive omitidas. */
    val pointEntries: List<FocusSource>
        get() = entries.filter {
            it.origin == FocusOrigin.SUBPOINT || it.origin == FocusOrigin.SECTION
        }
    /** Refs do ponto atual (subponto/seção) que entraram no prompt de alguma forma. */
    val pointSources: List<FocusSource>
        get() = pointEntries.filter {
            it.destination == FocusDestination.FOCUS_BLOCK ||
                it.destination == FocusDestination.DOSSIER ||
                it.destination == FocusDestination.PACK ||
                it.destination == FocusDestination.QUESTION
        }
    val omitted: List<FocusSource>
        get() = entries.filter { it.destination == FocusDestination.OMITTED }
    val isEmpty: Boolean get() = entries.isEmpty()

    fun destinationOf(key: String): FocusDestination? =
        entries.firstOrNull { it.key == key }?.destination
}

/** Entrada de texto já resolvida para uma ref (dossiê, pack, pergunta…). */
data class FocusText(
    val reference: String,
    val text: String,
    val unit: String? = null,
    val status: FocusStatus = FocusStatus.RESOLVED,
)

/**
 * Entrada do resolvedor. [resolvedTexts] é indexado pela chave canônica
 * (`NoteRefsCollector.sourceKey` para publicações; `bible|livro|cap|vers`
 * para Bíblia). As chaves de origem vêm do dossiê atual.
 */
data class FocusSourcesInput(
    val noteRefs: NoteRefs = NoteRefs.EMPTY,
    val noteSources: NoteSources = NoteSources(emptyList(), 0, 0),
    val resolvedTexts: Map<String, FocusText> = emptyMap(),
    val subPointKeys: Set<String> = emptySet(),
    val sectionKeys: Set<String> = emptySet(),
    val questionKeys: Set<String> = emptySet(),
    val packSources: List<EvidenceSource> = emptyList(),
    val questionSources: List<EvidenceSource> = emptyList(),
    val dossierRefs: Set<String> = emptySet(),
    val pinnedRefs: Set<String> = emptySet(),
    val maxChars: Int = MAX_FOCUS_BLOCK_CHARS,
)

const val MAX_FOCUS_BLOCK_CHARS = 4_000
const val FOCUS_BLOCK_HEADER = "## FONTES DO PONTO ATUAL"
private const val MAX_PACK_FOCUS_SOURCES = 8

/** Rótulo curto do status para a UI e para o bloco. */
fun focusStatusLabel(status: FocusStatus): String = when (status) {
    FocusStatus.RESOLVED -> "resolvida"
    FocusStatus.PARTIAL -> "parcial"
    FocusStatus.TEXT_NOT_FOUND -> "edição no acervo; trecho não encontrado"
    FocusStatus.EDITION_MISSING -> "edição ausente"
    FocusStatus.DOWNLOADING -> "baixando"
    FocusStatus.INDEXING -> "indexando"
    FocusStatus.INDEX_FAILED -> "indexação falhou"
    FocusStatus.UNSUPPORTED -> "formato não suportado"
}

object FocusSourceResolver {

    private data class NormalizedInput(
        val subPointKeys: Set<String>,
        val sectionKeys: Set<String>,
        val questionKeys: Set<String>,
        val packKeys: Set<String>,
        val dossierKeys: Set<String>,
        val pinnedKeys: Set<String>,
    )

    fun resolve(input: FocusSourcesInput): FocusSources {
        val normalized = NormalizedInput(
            subPointKeys = normalizeKeys(input.subPointKeys),
            sectionKeys = normalizeKeys(input.sectionKeys),
            questionKeys = normalizeKeys(input.questionKeys),
            packKeys = input.packSources.take(MAX_PACK_FOCUS_SOURCES)
                .mapNotNull { keyOf(it.reference) }.toSet(),
            dossierKeys = normalizeKeys(input.dossierRefs),
            pinnedKeys = normalizeKeys(input.pinnedRefs),
        )
        val entries = LinkedHashMap<String, FocusSource>()

        // 1. Espinha: todas as refs canônicas da nota, na ordem do coletor.
        noteRefEntries(input, normalized).forEach { entries.putIfAbsent(it.key, it) }

        // 2. Textos resolvidos que não vieram da nota.
        input.resolvedTexts.forEach { (key, text) ->
            if (key !in entries) {
                entries[key] = FocusSource(
                    key = key,
                    reference = text.reference,
                    kind = kindOf(text.reference),
                    origin = originOf(key, normalized),
                    status = text.status,
                    unit = text.unit,
                    text = text.text,
                    originalChars = text.text.length,
                    publicationKey = publicationKeyOf(text.reference),
                )
            }
        }

        // 3. Pack RAG (até 8) e pergunta: entram no inventário do turno mesmo
        //    quando não são refs da nota.
        input.packSources.take(MAX_PACK_FOCUS_SOURCES).forEach { source ->
            val key = keyOf(source.reference) ?: return@forEach
            entries.putIfAbsent(
                key,
                FocusSource(
                    key = key,
                    reference = source.reference,
                    kind = kindOf(source.reference),
                    origin = FocusOrigin.PACK,
                    status = FocusStatus.RESOLVED,
                    text = source.text,
                    originalChars = source.text.length,
                    destination = FocusDestination.PACK,
                    publicationKey = publicationKeyOf(source.reference),
                ),
            )
        }
        input.questionSources.forEach { source ->
            val key = keyOf(source.reference) ?: return@forEach
            val existing = entries[key]
            if (existing == null || existing.origin.priority() > FocusOrigin.QUESTION.priority()) {
                entries[key] = FocusSource(
                    key = key,
                    reference = source.reference,
                    kind = kindOf(source.reference),
                    origin = when {
                        key in normalized.subPointKeys -> FocusOrigin.SUBPOINT
                        key in normalized.sectionKeys -> FocusOrigin.SECTION
                        key in normalized.packKeys -> FocusOrigin.PACK
                        else -> FocusOrigin.QUESTION
                    },
                    status = FocusStatus.RESOLVED,
                    text = source.text,
                    originalChars = source.text.length,
                    destination = FocusDestination.QUESTION,
                    publicationKey = publicationKeyOf(source.reference),
                )
            }
        }

        // 4. Destino e motivo de cada entrada.
        val classified = entries.values.map { classify(it, normalized) }

        // 5. Bloco: só o que ainda não está no dossiê/pack/pergunta e tem texto.
        return buildBlock(classified, input.maxChars)
    }

    private fun noteRefEntries(
        input: FocusSourcesInput,
        normalized: NormalizedInput,
    ): List<FocusSource> {
        val out = mutableListOf<FocusSource>()
        input.noteRefs.publications.forEach { ref ->
            val key = NoteRefsCollector.sourceKey(ref)
            val note = findPublicationNoteSource(ref, input.noteSources)
            val text = input.resolvedTexts[key]
            out += FocusSource(
                key = key,
                reference = ref.raw,
                kind = SourceKind.PUBLICATION,
                origin = originOf(key, normalized),
                status = mergeStatus(note?.status, text?.status),
                unit = unitOf(ref),
                text = text?.text.orEmpty(),
                originalChars = text?.text?.length ?: 0,
                publicationLabel = note?.publicationLabel,
                publicationKey = note?.publicationKey ?: ref.pubKey,
            )
        }
        input.noteRefs.bibles.forEach { bible ->
            val key = bibleKey(bible)
            val note = findBibleNoteSource(bible, input.noteSources)
            val text = input.resolvedTexts[key]
            out += FocusSource(
                key = key,
                reference = "${bible.label} ${bible.chapter}:${bible.verse}",
                kind = SourceKind.BIBLE,
                origin = originOf(key, normalized),
                status = mergeStatus(note?.status, text?.status),
                unit = "${bible.chapter}:${bible.verse}",
                text = text?.text.orEmpty(),
                originalChars = text?.text?.length ?: 0,
                publicationLabel = "TNM",
            )
        }
        return out
    }

    private fun classify(
        source: FocusSource,
        normalized: NormalizedInput,
    ): FocusSource {
        if (source.kind == SourceKind.PUBLICATION &&
            source.publicationKey in setOf("be", "th")
        ) {
            return source.copy(
                destination = FocusDestination.INVENTORY,
                omissionReason = "orientação de oratória; não é fonte de conteúdo",
            )
        }
        if (source.status.isUnusable()) {
            return source.copy(
                destination = FocusDestination.OMITTED,
                omissionReason = omissionReason(source),
                originalChars = source.text.length,
                truncatedChars = 0,
            )
        }
        if (source.text.isBlank()) {
            return source.copy(
                destination = FocusDestination.OMITTED,
                omissionReason = "sem trecho utilizável no acervo",
                originalChars = 0,
                truncatedChars = 0,
            )
        }
        return when {
            source.key in normalized.dossierKeys ->
                source.copy(destination = FocusDestination.DOSSIER)
            source.key in normalized.pinnedKeys ->
                source.copy(destination = FocusDestination.PACK)
            source.origin == FocusOrigin.PACK ->
                source.copy(destination = FocusDestination.PACK)
            source.origin == FocusOrigin.QUESTION ->
                source.copy(destination = FocusDestination.QUESTION)
            source.origin == FocusOrigin.SUBPOINT || source.origin == FocusOrigin.SECTION ->
                source.copy(destination = FocusDestination.FOCUS_BLOCK)
            // Ref da nota fora do ponto: fica no inventário, nunca duplica o
            // texto no bloco do ponto.
            else -> source.copy(
                destination = FocusDestination.INVENTORY,
                omissionReason = "fora do ponto atual; disponível no inventário da nota",
            )
        }
    }

    private fun buildBlock(
        sources: List<FocusSource>,
        maxChars: Int,
    ): FocusSources {
        val result = sources.toMutableList()
        val textIndices = result.indices
            .filter { result[it].destination == FocusDestination.FOCUS_BLOCK }
            .sortedBy { result[it].origin.priority() }
        val refIndices = result.indices
            .filter {
                (result[it].origin == FocusOrigin.SUBPOINT ||
                    result[it].origin == FocusOrigin.SECTION) &&
                    result[it].destination in setOf(
                        FocusDestination.DOSSIER,
                        FocusDestination.PACK,
                        FocusDestination.QUESTION,
                    )
            }
            .sortedBy { result[it].origin.priority() }
        if (textIndices.isEmpty() && refIndices.isEmpty()) {
            return FocusSources(result, "", maxChars)
        }

        val usedTextIndices = mutableListOf<Int>()
        val usedRefIndices = mutableListOf<Int>()
        var remaining = maxChars - FOCUS_BLOCK_HEADER.length - 1
        var omittedByBudget = false

        for (index in textIndices) {
            val source = result[index]
            val prefix = "- ${source.reference} — ${statusLabel(source)}"
            val warningReserve = 64
            if (remaining <= prefix.length + 48 + warningReserve) {
                omittedByBudget = true
                result[index] = source.copy(
                    destination = FocusDestination.OMITTED,
                    omissionReason = "orçamento do bloco esgotado",
                    truncatedChars = 0,
                )
                continue
            }
            val text = source.text.trim()
            val available = remaining - prefix.length - 2 - warningReserve
            val truncated = text.length > available
            val usedText = if (truncated) {
                text.take(available).trimEnd() + "…"
            } else {
                text
            }
            val warning = if (truncated) {
                "  (texto truncado: ${usedText.length} de ${text.length} caracteres)"
            } else {
                ""
            }
            val cost = prefix.length + 1 + usedText.length + 1 + warning.length + 1
            if (cost > remaining) {
                omittedByBudget = true
                result[index] = source.copy(
                    destination = FocusDestination.OMITTED,
                    omissionReason = "orçamento do bloco esgotado",
                    truncatedChars = 0,
                )
                continue
            }
            result[index] = source.copy(
                status = if (truncated) FocusStatus.PARTIAL else source.status,
                text = usedText,
                originalChars = text.length,
                truncatedChars = usedText.length,
                destination = FocusDestination.FOCUS_BLOCK,
            )
            usedTextIndices += index
            remaining -= cost
        }

        for (index in refIndices) {
            val source = result[index]
            val line = "- ${source.reference} — ${destinationLabel(source.destination)}"
            val cost = line.length + 1
            if (cost > remaining) {
                omittedByBudget = true
                continue
            }
            usedRefIndices += index
            remaining -= cost
        }

        if (usedTextIndices.isEmpty() && usedRefIndices.isEmpty()) {
            return FocusSources(result, "", maxChars)
        }
        val block = buildString {
            append(FOCUS_BLOCK_HEADER).append('\n')
            usedTextIndices.forEach { index ->
                val source = result[index]
                append("- ").append(source.reference).append(" — ")
                    .append(statusLabel(source)).append('\n')
                append(source.text).append('\n')
                if (source.isTruncated) {
                    append("  (texto truncado: ").append(source.truncatedChars)
                        .append(" de ").append(source.originalChars)
                        .append(" caracteres)\n")
                }
            }
            usedRefIndices.forEach { index ->
                val source = result[index]
                append("- ").append(source.reference).append(" — ")
                    .append(destinationLabel(source.destination)).append('\n')
            }
            if (omittedByBudget) {
                append("- ... outras fontes do ponto não couberam no orçamento; ")
                    .append("use o inventário da nota para os metadados.\n")
            }
        }.trimEnd()
        val finalBlock = if (block.length <= maxChars) block else block.take(maxChars)
        return FocusSources(result, finalBlock, maxChars)
    }

    private fun destinationLabel(destination: FocusDestination): String = when (destination) {
        FocusDestination.DOSSIER -> "já no dossiê (texto não repetido)"
        FocusDestination.PACK -> "já no pack (texto não repetido)"
        FocusDestination.QUESTION -> "já na pergunta (texto não repetido)"
        FocusDestination.FOCUS_BLOCK -> "resolvida"
        FocusDestination.INVENTORY -> "no inventário da nota"
        FocusDestination.OMITTED -> "fora do prompt"
    }

    private fun originOf(key: String, normalized: NormalizedInput): FocusOrigin = when {
        key in normalized.subPointKeys -> FocusOrigin.SUBPOINT
        key in normalized.sectionKeys -> FocusOrigin.SECTION
        key in normalized.packKeys -> FocusOrigin.PACK
        key in normalized.questionKeys -> FocusOrigin.QUESTION
        else -> FocusOrigin.NOTE
    }

    private fun normalizeKeys(keys: Set<String>): Set<String> =
        keys.map { normalizeKey(it) }.toSet()

    /**
     * Aceita tanto ref crua ("w25.07 22 §§ 8-9") quanto chave canônica já
     * montada pelo coletor (`w|2025|7|||22|8|9` / `bible|jeremias|45|3`).
     */
    private fun normalizeKey(value: String): String = when {
        value.startsWith("bible|") || value.startsWith("raw|") -> value
        value.count { it == '|' } >= 5 -> value
        else -> keyOf(value) ?: rawKey(value) ?: value
    }

    private fun mergeStatus(note: SourceStatus?, text: FocusStatus?): FocusStatus {
        if (note != null && note != SourceStatus.RESOLVED) return note.toFocusStatus()
        if (text != null && text != FocusStatus.RESOLVED) return text
        if (note == SourceStatus.RESOLVED) return FocusStatus.RESOLVED
        return text ?: FocusStatus.EDITION_MISSING
    }

    private fun SourceStatus.toFocusStatus(): FocusStatus = when (this) {
        SourceStatus.RESOLVED -> FocusStatus.RESOLVED
        SourceStatus.EDITION_MISSING -> FocusStatus.EDITION_MISSING
        SourceStatus.TEXT_NOT_FOUND -> FocusStatus.TEXT_NOT_FOUND
        SourceStatus.DOWNLOADING -> FocusStatus.DOWNLOADING
        SourceStatus.INDEXING -> FocusStatus.INDEXING
        SourceStatus.INDEX_FAILED -> FocusStatus.INDEX_FAILED
        SourceStatus.UNSUPPORTED -> FocusStatus.UNSUPPORTED
    }

    private fun FocusStatus.isUnusable(): Boolean = when (this) {
        FocusStatus.RESOLVED, FocusStatus.PARTIAL -> false
        FocusStatus.TEXT_NOT_FOUND,
        FocusStatus.EDITION_MISSING,
        FocusStatus.DOWNLOADING,
        FocusStatus.INDEXING,
        FocusStatus.INDEX_FAILED,
        FocusStatus.UNSUPPORTED -> true
    }

    private fun omissionReason(source: FocusSource): String = when (source.status) {
        FocusStatus.EDITION_MISSING ->
            if (source.kind == SourceKind.BIBLE) "TNM ausente do acervo"
            else "edição ausente do acervo"
        FocusStatus.TEXT_NOT_FOUND ->
            if (source.kind == SourceKind.BIBLE) "TNM presente; versículo não encontrado"
            else "edição no acervo; trecho não encontrado"
        FocusStatus.DOWNLOADING -> "download em andamento"
        FocusStatus.INDEXING -> "indexação em andamento"
        FocusStatus.INDEX_FAILED -> "edição importada, mas a indexação falhou"
        FocusStatus.UNSUPPORTED -> "formato não suportado para indexação"
        FocusStatus.PARTIAL, FocusStatus.RESOLVED -> "sem trecho utilizável no acervo"
    }

    private fun statusLabel(source: FocusSource): String = focusStatusLabel(source.status)

    private fun findPublicationNoteSource(
        ref: RefDetector.DetectedRef,
        sources: NoteSources,
    ): NoteSource? {
        val key = NoteRefsCollector.sourceKey(ref)
        return sources.sources.firstOrNull {
            it.kind == SourceKind.PUBLICATION && (
                it.refStatus?.ref?.let { r -> NoteRefsCollector.sourceKey(r) } == key ||
                    normalizeText(it.ref) == normalizeText(ref.raw)
                )
        }
    }

    private fun findBibleNoteSource(
        bible: RefDetector.BibleRef,
        sources: NoteSources,
    ): NoteSource? = sources.sources.firstOrNull {
        it.kind == SourceKind.BIBLE &&
            normalizeText(it.book.orEmpty()) == normalizeText(bible.label) &&
            it.chapter == bible.chapter &&
            it.verse == bible.verse
    }

    private fun unitOf(ref: RefDetector.DetectedRef): String? = buildList {
        ref.article?.takeIf { it.isNotBlank() }?.let { add("artigo $it") }
        ref.chapter?.takeIf { it.isNotBlank() }?.let { add(it) }
        ref.page?.let { start ->
            val end = ref.pageEnd?.takeIf { it != start }
            add(if (end != null) "pág. $start-$end" else "pág. $start")
        }
        ref.paragraph?.let { start ->
            val end = ref.paragraphEnd?.takeIf { it != start }
            add(if (end != null) "§$start-$end" else "§$start")
        }
    }.joinToString(" ").ifBlank { null }

    private fun kindOf(reference: String): SourceKind =
        if (RefDetector.detectBible(reference).isNotEmpty()) SourceKind.BIBLE
        else SourceKind.PUBLICATION

    private fun publicationKeyOf(reference: String): String? =
        RefDetector.detect(reference).firstOrNull()?.pubKey

    fun keyOf(reference: String): String? {
        RefDetector.detectBible(reference).firstOrNull()?.let { return bibleKey(it) }
        RefDetector.detect(reference).firstOrNull()?.let { return NoteRefsCollector.sourceKey(it) }
        return null
    }

    private fun rawKey(reference: String): String? =
        normalizeText(reference).takeIf { it.isNotBlank() }?.let { "raw|$it" }

    fun bibleKey(bible: RefDetector.BibleRef): String =
        "bible|${normalizeText(bible.label)}|${bible.chapter}|${bible.verse}"

    private fun FocusOrigin.priority(): Int = when (this) {
        FocusOrigin.SUBPOINT -> 0
        FocusOrigin.SECTION -> 1
        FocusOrigin.PACK -> 2
        FocusOrigin.QUESTION -> 3
        FocusOrigin.NOTE -> 4
    }
}
