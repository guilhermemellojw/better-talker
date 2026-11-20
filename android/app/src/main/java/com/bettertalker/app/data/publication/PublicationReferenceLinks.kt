package com.bettertalker.app.data.publication

import com.bettertalker.app.data.util.EditionStatus
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.normalizeText

/**
 * UX-REF-1B.1 — ocorrência de referência de publicação em texto livre.
 *
 * O texto visível NUNCA é reescrito: [start]/[endExclusive] apontam para o
 * mesmo `raw` dentro da mensagem original. [detection] preserva a validação
 * feita pelo [RefDetector]; quando ausente, o resolvedor tenta revalidar o
 * `raw` (útil em testes e em chamadas antigas).
 *
 * `publicationKey`/`editionKey` só são preenchidos quando o detector os
 * conhece. Página/§ nunca são inferidos fora de [detection].
 */
data class PublicationReferenceOccurrence(
    val raw: String,
    val start: Int,
    val endExclusive: Int,
    val publicationKey: String?,
    val titleHint: String?,
    val editionKey: String?,
    val article: String? = null,
    val chapter: String? = null,
    val page: Int? = null,
    val pageEnd: Int? = null,
    val paragraph: Int? = null,
    val paragraphEnd: Int? = null,
    /**
     * Offsets do trecho literal (título + unidade) no texto original.
     * O span visual continua sendo [start]/[endExclusive]; este trecho é o
     * que o resolvedor deve revalidar quando a unidade fica fora do título.
     */
    val validationStart: Int? = null,
    val validationEndExclusive: Int? = null,
    /** Resultado bruto do detector; não é uma segunda fonte de verdade. */
    val detection: RefDetector.DetectedRef? = null,
) {
    init {
        require(start >= 0) { "start deve ser >= 0" }
        require(endExclusive > start) { "endExclusive deve ser > start" }
        require(raw.isNotBlank()) { "raw não pode ser vazio" }
        require((validationStart == null) == (validationEndExclusive == null)) {
            "validationStart e validationEndExclusive devem ser ambos nulos ou ambos preenchidos"
        }
        require(validationStart == null || validationStart >= start) {
            "validationStart não pode anteceder o span visual"
        }
        require(validationEndExclusive == null || validationEndExclusive > endExclusive) {
            "validationEndExclusive deve incluir a unidade após o span visual"
        }
    }

    val range: IntRange get() = start until endExclusive
}

/** Publicação disponível no acervo local, sem texto recuperado. */
data class PublicationCandidate(
    val attachmentId: String,
    val fileName: String,
    val publicationKey: String?,
    val publicationTitle: String,
    val editionLabel: String?,
    val format: String,
    val indexed: Boolean,
    val edition: EditionStatus,
)

/**
 * Unidade documental REAL do índice. Todos os campos vêm de
 * `PublicationUnitEntity`; null significa "não identificada", nunca
 * "provavelmente esta".
 */
data class PublicationDocumentUnit(
    val kind: PublicationUnitKind,
    val number: String? = null,
    val title: String? = null,
    val canonicalKey: String? = null,
    val startOrd: Int? = null,
    val endOrd: Int? = null,
    val startPage: Int? = null,
    val endPage: Int? = null,
)

/**
 * Alvo do leitor com a edição FIXADA.
 *
 * [expectedAttachmentId] é a identidade do arquivo local. Ele deve ser
 * revalidado antes de abrir o conteúdo; se sumir/mudar, o resultado é
 * `EditionChanged`, nunca outra edição.
 *
 * [evidenceId] e [usedText] ficam nulos nesta fase: disponibilidade ou
 * recuperação não provam que o Mentor usou o trecho.
 */
data class PublicationReaderTarget(
    val rawReference: String,
    val displayRef: String,
    val canonicalRef: String?,
    val publicationKey: String?,
    val publicationTitle: String,
    val editionKey: String?,
    val editionLabel: String?,
    val expectedAttachmentId: String,
    val fileName: String,
    val sourceLabel: String,
    val unit: PublicationDocumentUnit? = null,
    val page: Int? = null,
    val pageEnd: Int? = null,
    val paragraph: Int? = null,
    val paragraphEnd: Int? = null,
    /** Id do trecho que sustenta o status; não é evidência do turno. */
    val sourcePassageId: String? = null,
    val partial: Boolean = false,
    val truncated: Boolean = false,
    /** Só quando houver vínculo documental real com o turno. */
    val evidenceId: String? = null,
    /** Só quando o texto tiver sido efetivamente exposto ao modelo. */
    val usedText: String? = null,
)

/** Estados de resolução da referência de publicação. */
sealed interface PublicationReferenceResolution {
    data class Available(
        val target: PublicationReaderTarget,
    ) : PublicationReferenceResolution

    data class Partial(
        val target: PublicationReaderTarget,
        val warning: String,
    ) : PublicationReferenceResolution

    data class NotImported(
        val reference: String,
        val publicationTitle: String?,
        val candidates: List<PublicationCandidate> = emptyList(),
    ) : PublicationReferenceResolution

    data class NotReady(
        val reference: String,
        val edition: EditionStatus,
        val detail: String? = null,
    ) : PublicationReferenceResolution

    data class TextNotFound(
        val reference: String,
        val publicationTitle: String,
        val editionLabel: String?,
    ) : PublicationReferenceResolution

    data class Ambiguous(
        val reference: String,
        val candidates: List<PublicationCandidate>,
    ) : PublicationReferenceResolution

    data class EditionChanged(
        val reference: String,
        val expectedAttachmentId: String,
    ) : PublicationReferenceResolution

    data class NotRecognized(
        val raw: String,
    ) : PublicationReferenceResolution

    data class ReadFailed(
        val reference: String,
        val reason: String? = null,
    ) : PublicationReferenceResolution
}

/** Aviso curto e tipado para a UI da UX-REF-1B.3. */
data class PublicationReferenceNotice(
    val title: String,
    val message: String,
    val openLibrary: Boolean,
    val edition: EditionStatus? = null,
)

/**
 * Tradutor puro de estado → conteúdo do AlertDialog. Nenhuma decisão é
 * tomada por parsing de mensagem: o estado sealed manda.
 */
fun publicationReferenceNotice(
    resolution: PublicationReferenceResolution,
): PublicationReferenceNotice? = when (resolution) {
    is PublicationReferenceResolution.Available,
    is PublicationReferenceResolution.Partial,
    -> null

    is PublicationReferenceResolution.NotImported -> PublicationReferenceNotice(
        title = "Publicação ainda não disponível",
        message = "Para consultar essa referência, importe a publicação " +
            "correspondente na Biblioteca do aplicativo.",
        openLibrary = true,
    )

    is PublicationReferenceResolution.NotReady -> when (resolution.edition) {
        EditionStatus.DOWNLOADING -> PublicationReferenceNotice(
            title = "Publicação baixando",
            message = "A publicação está sendo baixada. Acompanhe o andamento na Biblioteca.",
            openLibrary = true,
            edition = resolution.edition,
        )
        EditionStatus.INDEXING -> PublicationReferenceNotice(
            title = "Publicação sendo preparada",
            message = "A publicação foi importada, mas o índice ainda está sendo construído. " +
                "Tente novamente quando a indexação terminar.",
            openLibrary = true,
            edition = resolution.edition,
        )
        EditionStatus.INDEX_FAILED -> PublicationReferenceNotice(
            title = "Não foi possível indexar",
            message = "A publicação está no acervo, mas a indexação falhou. " +
                "Abra a Biblioteca para reindexar ou importar outra cópia.",
            openLibrary = true,
            edition = resolution.edition,
        )
        EditionStatus.UNSUPPORTED -> PublicationReferenceNotice(
            title = "Formato não suportado",
            message = "Este arquivo não pode ser indexado. " +
                "Importe a publicação em JWPUB, EPUB, DOCX ou PDF (quando disponível).",
            openLibrary = true,
            edition = resolution.edition,
        )
        EditionStatus.MISSING -> PublicationReferenceNotice(
            title = "Publicação não disponível",
            message = "O arquivo desta publicação não está no acervo local. " +
                "Abra a Biblioteca para importar a edição correspondente.",
            openLibrary = true,
            edition = resolution.edition,
        )
        EditionStatus.READY -> PublicationReferenceNotice(
            title = "Publicação indisponível",
            message = "Não foi possível abrir esta publicação agora. " +
                "Verifique a Biblioteca do aplicativo.",
            openLibrary = true,
            edition = resolution.edition,
        )
    }

    is PublicationReferenceResolution.TextNotFound -> PublicationReferenceNotice(
        title = "Trecho não encontrado",
        message = "A publicação está disponível, mas não foi possível localizar " +
            "esse trecho na edição importada.",
        openLibrary = true,
    )

    is PublicationReferenceResolution.Ambiguous -> PublicationReferenceNotice(
        title = "Mais de uma publicação possível",
        message = "Encontramos mais de uma edição compatível. " +
            "Escolha qual deseja consultar na Biblioteca.",
        openLibrary = true,
    )

    is PublicationReferenceResolution.EditionChanged -> PublicationReferenceNotice(
        title = "Publicação alterada",
        message = "O arquivo desta referência não está mais disponível. " +
            "Verifique a Biblioteca.",
        openLibrary = true,
    )

    is PublicationReferenceResolution.NotRecognized -> PublicationReferenceNotice(
        title = "Referência não identificada",
        message = "Não foi possível identificar essa referência de publicação.",
        openLibrary = false,
    )

    is PublicationReferenceResolution.ReadFailed -> PublicationReferenceNotice(
        title = "Não foi possível abrir",
        message = resolution.reason?.takeIf { it.isNotBlank() }
            ?: "Tente novamente em alguns instantes.",
        openLibrary = false,
    )
}

/**
 * Detector de spans de publicações.
 *
 * Estratégia: usa [RefDetector.detect] como ÚNICA validação das regras de
 * identificação e localiza todas as ocorrências reais do `raw` no texto.
 * Isso preserva repetições (o detector deduplica por edição+unidade) sem
 * duplicar a gramática de referências. Um fallback pequeno reconhece
 * símbolos isolados inequívocos (`it-1`, `lmd`), nunca títulos genéricos ou
 * números soltos.
 *
 * Código inline, code fences, URLs e links Markdown ficam protegidos. Os
 * spans retornados nunca se sobrepõem.
 */
object PublicationReferenceSpanDetector {

    private val BARE_SYMBOL_RE = Regex(
        """(?<![\p{L}\p{N}_-])([A-Za-z]{2,5}(?:-[1-3])?)(?![\p{L}\p{N}_-])""",
    )

    /** Unidade explícita logo depois de um título por extenso. */
    private val TITLE_UNIT_AFTER_RE = Regex(
        """^\s*[^\p{L}\p{N}]{0,4}\s*(?:(?:cap(?:[íi]tulo)?s?\.?|li[cç][aã]o|estudo|""" +
            """p[áa]g(?:ina)?s?\.?|pg\.?|p\.?|§{1,2}|pontos?|""" +
            """par[áa]g(?:rafo)?s?\.?|n\.?\s*[ºo°]?)\s*\d{1,3}|\d{1,3}\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun detect(text: String): List<PublicationReferenceOccurrence> {
        if (text.isBlank()) return emptyList()
        val protected = protectedRanges(text)
        val candidates = mutableListOf<Candidate>()
        val detections = RefDetector.detect(text)
        // Título compartilhado por mais de uma publicação (ex.: lff/lffi)
        // nunca é linkável: o texto não prova qual edição é.
        val ambiguousTitles = detections
            .filter(::isTitleOnly)
            .groupBy { normalizeText(it.raw) }
            .filterValues { refs -> refs.map { it.pubKey.lowercase() }.distinct().size > 1 }
            .keys

        detections.forEach { ref ->
            val raw = ref.raw.trim()
            if (raw.isEmpty() || !isLinkable(ref, raw)) return@forEach
            if (isTitleOnly(ref) && normalizeText(raw) in ambiguousTitles) return@forEach
            val matches = titleSearchRaws(ref, raw)
                .flatMap { search -> findAll(text, search) }
                .distinctBy { (start, end) -> start to end }
                .sortedBy { it.first }
            matches.forEach { (start, end) ->
                if (overlapsAny(start, end, protected) ||
                    !isDelimited(text, start, end, text.substring(start, end))
                ) {
                    return@forEach
                }
                // O detector por título devolve só o título como `raw`.
                // Linkamos apenas a ocorrência que traz a unidade explícita
                // logo depois — a menção solta no meio da prosa não vira
                // link. A unidade vem de uma nova passada do detector sobre
                // o trecho local; nunca é inferida por regex própria.
                val occurrenceRef = if (isTitleOnly(ref)) {
                    if (!hasUnitAfter(text, end)) return@forEach
                    titleUnitRef(text, start, end, ref) ?: return@forEach
                } else {
                    ref
                }
                val validationEnd = if (isTitleOnly(ref)) {
                    minOf(text.length, end + 80)
                } else {
                    null
                }
                candidates += Candidate(start, end, occurrenceRef, validationEnd)
            }
        }

        // Símbolo isolado inequívoco: it-1, lmd, bhs…
        // Não reconhece títulos genéricos nem números.
        BARE_SYMBOL_RE.findAll(text).forEach { match ->
            val token = match.value
            if (!isSafeBareSymbol(token)) return@forEach
            val start = match.range.first
            val end = match.range.last + 1
            if (overlapsAny(start, end, protected) ||
                !isDelimited(text, start, end, token)
            ) {
                return@forEach
            }
            val canonical = PubCatalog.resolveSymbol(token) ?: return@forEach
            if (canonical == "nwt") return@forEach
            val synthetic = RefDetector.DetectedRef(
                raw = token,
                kind = RefDetector.Kind.BOOK,
                pubKey = canonical,
                editionKey = "book|$canonical",
                label = PubCatalog.titleOf(canonical) ?: token,
            )
            candidates += Candidate(start, end, synthetic)
        }

        // Mais longo primeiro no mesmo início; depois ordem determinística.
        val ordered = candidates.sortedWith(
            compareBy<Candidate> { it.start }
                .thenByDescending { it.end - it.start }
                .thenByDescending { it.ref.hasUnit() }
                .thenBy { it.ref.editionKey },
        )
        val out = mutableListOf<PublicationReferenceOccurrence>()
        var lastEnd = -1
        ordered.forEach { candidate ->
            if (candidate.start < lastEnd) return@forEach
            val ref = candidate.ref
            out += PublicationReferenceOccurrence(
                raw = text.substring(candidate.start, candidate.end),
                start = candidate.start,
                endExclusive = candidate.end,
                publicationKey = ref.pubKey.takeIf { it.isNotBlank() },
                titleHint = titleHintOf(ref),
                editionKey = ref.editionKey.takeIf { it.isNotBlank() },
                article = ref.article,
                chapter = ref.chapter,
                page = ref.page,
                pageEnd = ref.pageEnd,
                paragraph = ref.paragraph,
                paragraphEnd = ref.paragraphEnd,
                validationStart = candidate.validationEndExclusive?.let { candidate.start },
                validationEndExclusive = candidate.validationEndExclusive,
                detection = ref,
            )
            lastEnd = candidate.end
        }
        return out.sortedBy { it.start }
    }

    private data class Candidate(
        val start: Int,
        val end: Int,
        val ref: RefDetector.DetectedRef,
        val validationEndExclusive: Int? = null,
    )

    private fun isLinkable(ref: RefDetector.DetectedRef, raw: String): Boolean {
        if (ref.pubKey.equals("nwt", ignoreCase = true)) return false
        if (RefDetector.detectBible(raw).isNotEmpty()) return false
        // Título por extenso: a validação da unidade é feita ocorrência a
        // ocorrência em [hasUnitAfter]. O detector pode ter associado a
        // unidade a outra menção do mesmo título.
        if (isTitleOnly(ref)) return true
        if (ref.hasUnit()) return true
        if (isSymbolToken(raw)) return true
        // Edição de revista explícita (w25.07, g 1/24…) sem unidade ainda é
        // uma referência reconhecível; o resolvedor dirá se há conteúdo.
        return ref.kind == RefDetector.Kind.MAGAZINE &&
            ref.editionKey.split('|').size >= 3
    }

    private fun isSafeBareSymbol(token: String): Boolean {
        val canonical = PubCatalog.resolveSymbol(token.trim()) ?: return false
        if (canonical == "nwt") return false
        val clean = token.trim()
        if (clean.length < 3 && !clean.contains('-')) return false
        // "be"/"th" são orientação e só entram com unidade explícita, via
        // RefDetector; um "it"/"w"/"g" isolado é ruído.
        if (clean.equals("be", true) || clean.equals("th", true)) return false
        if (RefDetector.detectBible(clean).isNotEmpty()) return false
        return true
    }

    private fun isSymbolToken(raw: String): Boolean {
        val clean = raw.trim()
        if (clean.isEmpty() || clean.any { it.isWhitespace() }) return false
        return PubCatalog.resolveSymbol(clean) != null
    }

    /**
     * Títulos do catálogo podem carregar pontuação editorial que não existe
     * no texto ("Seja Feliz para Sempre!"). Busca as duas formas, sem
     * reescrever o texto: o span final ainda é o substring original.
     */
    private fun titleSearchRaws(
        ref: RefDetector.DetectedRef,
        raw: String,
    ): List<String> {
        if (!isTitleOnly(ref)) return listOf(raw)
        val stripped = raw.trim().trim { it in "\"'“”‘’!?.,;:" }
        return listOf(raw, stripped).filter { it.isNotBlank() }.distinct()
    }

    /** `raw` é apenas o título por extenso (sem símbolo nem unidade). */
    private fun isTitleOnly(ref: RefDetector.DetectedRef): Boolean {
        if (ref.kind != RefDetector.Kind.BOOK) return false
        val title = PubCatalog.titleOf(ref.pubKey)?.let(::normalizeText) ?: return false
        return normalizeText(ref.raw) == title
    }

    private fun hasUnitAfter(text: String, end: Int): Boolean {
        val tail = text.substring(end, minOf(text.length, end + 48))
        return TITLE_UNIT_AFTER_RE.containsMatchIn(tail)
    }

    /**
     * Revalida o título já validado pelo [RefDetector] junto da unidade
     * imediatamente posterior. Devolve a detecção com capítulo/página/§
     * preenchidos; null quando o detector não reconhece a unidade.
     */
    private fun titleUnitRef(
        text: String,
        start: Int,
        end: Int,
        ref: RefDetector.DetectedRef,
    ): RefDetector.DetectedRef? {
        val window = text.substring(start, minOf(text.length, end + 80))
        return RefDetector.detect(window).firstOrNull {
            it.pubKey.equals(ref.pubKey, ignoreCase = true) && it.hasUnit()
        }
    }

    private fun titleHintOf(ref: RefDetector.DetectedRef): String? {
        if (ref.kind != RefDetector.Kind.BOOK) return null
        val title = PubCatalog.titleOf(ref.pubKey)?.let(::normalizeText) ?: return null
        val raw = normalizeText(ref.raw)
        return ref.raw.takeIf {
            raw.contains(title) && !isSymbolToken(ref.raw)
        }
    }

    private fun RefDetector.DetectedRef.hasUnit(): Boolean =
        !article.isNullOrBlank() || !chapter.isNullOrBlank() ||
            page != null || pageEnd != null ||
            paragraph != null || paragraphEnd != null

    private fun findAll(text: String, raw: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var from = 0
        while (from <= text.length - raw.length) {
            val index = text.indexOf(raw, from, ignoreCase = true)
            if (index < 0) break
            out += index to (index + raw.length)
            from = index + 1
        }
        return out
    }

    private fun isDelimited(
        text: String,
        start: Int,
        end: Int,
        raw: String,
    ): Boolean {
        if (raw.isEmpty()) return false
        if (start > 0 && isWordChar(text[start - 1]) && isWordChar(raw.first())) {
            return false
        }
        if (end < text.length && isWordChar(text[end]) && isWordChar(raw.last())) {
            return false
        }
        // Evita transformar cap:vers em publicação (ex.: "Re 1:1").
        if (end < text.length && text[end] == ':' && raw.last().isDigit()) {
            return false
        }
        return true
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun overlapsAny(start: Int, end: Int, ranges: List<IntRange>): Boolean =
        ranges.any { start < it.last + 1 && end - 1 >= it.first }

    private fun protectedRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        ranges += fencedCodeRanges(text)
        ranges += inlineCodeRanges(text)
        val definitions = REFERENCE_DEFINITION_RE.findAll(text).toList()
        ranges += definitions.map { it.range }
        ranges += markdownLinkRanges(
            text = text,
            definedLabels = definitions.mapNotNull { definitionLabel(it.value) }.toSet(),
        )
        Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { ranges += it.range }
        // URL sem esquema (jw.org/lmd) também é destino, não referência.
        Regex(
            """(?i)\b(?:[a-z0-9-]+\.)+(?:com|org|net|br|io|dev)(?:/\S*)?""",
        ).findAll(text).forEach { ranges += it.range }
        return ranges
    }

    /**
     * Cercas de código Markdown com crases ou tils, de qualquer comprimento.
     * A cerca de fechamento precisa ter o mesmo caractere e pelo menos o
     * mesmo número de marcadores; sem fechamento, protege até o fim.
     */
    private fun fencedCodeRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart)
                .let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            val open = FENCE_OPEN_RE.find(line)
            if (open == null) {
                lineStart = if (lineEnd < text.length) lineEnd + 1 else text.length
                continue
            }
            val fence = open.groupValues[1]
            val marker = fence[0]
            val minLength = fence.length
            val closing = Regex(
                "^ {0,3}" + Regex.escape(marker.toString()) + "{" + minLength + ",}[ \\t]*$",
            )
            var search = if (lineEnd < text.length) lineEnd + 1 else text.length
            var end = text.length
            while (search < text.length) {
                val closeLineEnd = text.indexOf('\n', search)
                    .let { if (it < 0) text.length else it }
                if (closing.matches(text.substring(search, closeLineEnd))) {
                    end = closeLineEnd
                    break
                }
                search = if (closeLineEnd < text.length) closeLineEnd + 1 else text.length
            }
            out += lineStart until end
            lineStart = if (end < text.length) end + 1 else text.length
        }
        return out
    }

    /**
     * Código inline com uma ou mais crases. A cerca de fechamento precisa
     * ter o mesmo comprimento da abertura; spans multilinha são ignorados
     * para não proteger uma região grande por engano.
     */
    private fun inlineCodeRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '`' || isEscaped(text, i)) {
                i++
                continue
            }
            var run = 1
            while (i + run < text.length && text[i + run] == '`') run++
            var search = i + run
            var end = -1
            while (search < text.length) {
                val c = text[search]
                if (c == '\n') break
                if (c == '`' && !isEscaped(text, search)) {
                    var closeRun = 1
                    while (search + closeRun < text.length &&
                        text[search + closeRun] == '`'
                    ) {
                        closeRun++
                    }
                    if (closeRun == run) {
                        end = search + closeRun
                        break
                    }
                    search += closeRun
                    continue
                }
                search++
            }
            if (end > i) {
                out += i until end
                i = end
            } else {
                i += run
            }
        }
        return out
    }

    /**
     * Links Markdown inline e por referência, incluindo colchetes/parênteses
     * aninhados. O span começa no `[`; imagens (`![alt](url)`) também ficam
     * protegidas a partir do `[`.
     */
    private fun markdownLinkRanges(
        text: String,
        definedLabels: Set<String>,
    ): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '[' || isEscaped(text, i)) {
                i++
                continue
            }
            val close = matchingDelimiter(text, i, '[', ']')
            if (close < 0) {
                i++
                continue
            }
            val next = close + 1
            val end = when {
                next < text.length && text[next] == '(' ->
                    matchingDelimiter(text, next, '(', ')')
                next < text.length && text[next] == '[' ->
                    matchingDelimiter(text, next, '[', ']')
                else -> {
                    // Link por referência no formato curto: [texto], quando
                    // existe uma definição [texto]: ...
                    val label = normalizeText(text.substring(i + 1, close))
                    if (label.isNotBlank() && label in definedLabels) close else -1
                }
            }
            if (end >= 0) {
                out += i until end + 1
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun definitionLabel(line: String): String? =
        REFERENCE_DEFINITION_LABEL_RE.find(line)
            ?.groupValues
            ?.get(1)
            ?.let(::normalizeText)
            ?.takeIf { it.isNotBlank() }

    private fun matchingDelimiter(
        text: String,
        openIndex: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            val c = text[i]
            if (isEscaped(text, i)) {
                i++
                continue
            }
            when (c) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    private val FENCE_OPEN_RE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val REFERENCE_DEFINITION_RE =
        Regex("""(?m)^(?: {0,3}> ?)* {0,3}\[[^\]]+]:[ \t]*\S.*$""")
    private val REFERENCE_DEFINITION_LABEL_RE =
        Regex("""^(?: {0,3}> ?)* {0,3}\[([^\]]+)]:""")
}
