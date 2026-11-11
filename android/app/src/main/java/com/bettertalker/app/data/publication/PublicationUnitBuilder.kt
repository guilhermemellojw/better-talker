package com.bettertalker.app.data.publication

import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.db.PublicationUnitEntity
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.normalizeText

/** Tipo estrutural de uma unidade de publicação. */
enum class PublicationUnitKind {
    PART,
    CHAPTER,
    ARTICLE,
    LESSON,
    APPENDIX,
    SECTION,
    DOCUMENT,
}

/**
 * 5.6a: constrói o índice estrutural de uma publicação a partir das passages
 * já extraídas. Puro e determinístico.
 *
 * Regras:
 * - agrupa por runs contíguos de [PassageEntity.section] (não junta títulos
 *   repetidos distantes, como índice + corpo);
 * - cabeçalhos explícitos ("Parte 1", "Capítulo 2", "Artigo 3", "Lição 4",
 *   "Apêndice A", "Seção 5") viram fronteiras de unidade; subtítulos sem
 *   prefixo entram na unidade estrutural anterior;
 * - sem cabeçalho explícito, cada run vira unidade do tipo padrão da
 *   publicação (revista/it/rsg = artigo; lmd/th = lição; livro comum =
 *   capítulo; manual/apostila = seção);
 * - sem nenhum heading, há uma única unidade DOCUMENTO;
 * - EPUB/PDF/DOCX/RTF/TXT/ZIP viram uma única unidade DOCUMENTO;
 * - NWT fica fora (o leitor TNM usa refs de versículo).
 */
object PublicationUnitBuilder {

    private data class Run(
        val section: String,
        val startIndex: Int,
        val passages: List<PassageEntity>,
    )

    private data class Heading(
        val kind: PublicationUnitKind,
        val number: String?,
    )

    private data class StructuralPoint(
        val startIndex: Int,
        val run: Run,
        val heading: Heading,
    )

    private data class PendingUnit(
        val kind: PublicationUnitKind,
        val number: String?,
        val title: String,
        val startOrd: Int,
        val endOrd: Int,
        val startPage: Int?,
        val endPage: Int?,
        val parent: PendingUnit?,
    )

    private val HEADING_RE = Regex(
        "^(parte|capitulo|artigo|licao|apendice|secao)\\s+" +
            "([0-9]+[a-z]?|[a-z])\\b"
    )

    private val MAGAZINE_RE = Regex("""^(wp?|gn?)(?=[\d\s._-])""")
    private val DOCUMENT_FORMATS = setOf(
        "epub", "pdf", "docx", "rtf", "zip", "txt", "md",
        "document", "unsupported", "bin",
    )

    fun build(
        attachmentId: String,
        symbol: String,
        pubKind: String?,
        passages: List<PassageEntity>,
    ): List<PublicationUnitEntity> {
        if (attachmentId.isBlank()) return emptyList()
        val ordered = passages
            .filter { it.attachmentId == attachmentId }
            .sortedWith(compareBy<PassageEntity> { it.ord }.thenBy { it.id })
        if (ordered.isEmpty()) return emptyList()

        val kind = pubKind?.trim()?.lowercase()
        if (symbol.trim().equals("nwt", ignoreCase = true) || kind == "bible") {
            return emptyList()
        }
        if (kind in DOCUMENT_FORMATS) {
            return toEntities(
                attachmentId = attachmentId,
                symbol = symbol,
                pending = listOf(documentUnit(ordered)),
            )
        }

        val runs = runsOf(ordered)
        val explicit = runs.mapNotNull { run ->
            parseHeading(run.section)?.let { heading ->
                StructuralPoint(run.startIndex, run, heading)
            }
        }
        val pending = if (explicit.isNotEmpty()) {
            buildExplicit(ordered, explicit)
        } else {
            buildFallback(runs, symbol, pubKind)
        }
        return toEntities(attachmentId, symbol, pending)
    }

    private fun runsOf(passages: List<PassageEntity>): List<Run> {
        val result = mutableListOf<Run>()
        var start = 0
        var section = cleanSection(passages.first().section)
        for (i in 1 until passages.size) {
            val next = cleanSection(passages[i].section)
            if (next != section) {
                result += Run(section, start, passages.subList(start, i))
                start = i
                section = next
            }
        }
        result += Run(section, start, passages.subList(start, passages.size))
        return result
    }

    /**
     * Cabeçalho explícito abre uma unidade e ela absorve os subtítulos até o
     * próximo cabeçalho explícito. Isso mantém "Capítulo 3" com o corpo
     * inteiro, mesmo que o extrator troque a seção em cada subtítulo.
     */
    private fun buildExplicit(
        ordered: List<PassageEntity>,
        explicit: List<StructuralPoint>,
    ): List<PendingUnit> {
        val result = mutableListOf<PendingUnit>()
        val first = explicit.first()
        if (first.startIndex > 0) {
            result += documentUnit(ordered.subList(0, first.startIndex))
        }

        var currentPart: PendingUnit? = null
        explicit.forEachIndexed { index, point ->
            val nextStart = explicit.getOrNull(index + 1)?.startIndex
            val endIndex = nextStart?.minus(1) ?: ordered.lastIndex
            val covered = ordered.subList(point.startIndex, endIndex + 1)
            val parent = when (point.heading.kind) {
                PublicationUnitKind.PART,
                PublicationUnitKind.APPENDIX,
                PublicationUnitKind.DOCUMENT -> null
                else -> currentPart
            }
            val unit = pendingUnit(
                kind = point.heading.kind,
                number = point.heading.number,
                title = point.run.section,
                passages = covered,
                parent = parent,
            )
            result += unit
            if (point.heading.kind == PublicationUnitKind.PART) currentPart = unit
        }
        return result
    }

    private fun buildFallback(
        runs: List<Run>,
        symbol: String,
        pubKind: String?,
    ): List<PendingUnit> {
        val fallback = fallbackKind(symbol, pubKind)
        return runs.map { run ->
            val kind = if (run.section.isBlank()) PublicationUnitKind.DOCUMENT else fallback
            pendingUnit(
                kind = kind,
                number = null,
                title = run.section,
                passages = run.passages,
                parent = null,
            )
        }
    }

    private fun pendingUnit(
        kind: PublicationUnitKind,
        number: String?,
        title: String,
        passages: List<PassageEntity>,
        parent: PendingUnit?,
    ): PendingUnit {
        val (startPage, endPage) = pageRange(passages)
        return PendingUnit(
            kind = kind,
            number = number,
            title = title.ifBlank { "Documento" },
            startOrd = passages.first().ord,
            endOrd = passages.last().ord,
            startPage = startPage,
            endPage = endPage,
            parent = parent,
        )
    }

    private fun documentUnit(passages: List<PassageEntity>): PendingUnit =
        pendingUnit(
            kind = PublicationUnitKind.DOCUMENT,
            number = null,
            title = "Documento",
            passages = passages,
            parent = null,
        )

    private fun pageRange(passages: List<PassageEntity>): Pair<Int?, Int?> {
        val pages = passages.mapNotNull { it.page }
        return pages.firstOrNull() to pages.lastOrNull()
    }

    private fun toEntities(
        attachmentId: String,
        symbol: String,
        pending: List<PendingUnit>,
    ): List<PublicationUnitEntity> {
        val ids = pending.indices.map { "$attachmentId-u$it" }
        val seenKeys = mutableMapOf<String, Int>()
        return pending.mapIndexed { index, unit ->
            val parentIndex = unit.parent?.let { parent ->
                pending.indexOfFirst { it === parent }
            }?.takeIf { it >= 0 }
            PublicationUnitEntity(
                id = ids[index],
                attachmentId = attachmentId,
                parentId = parentIndex?.let { ids[it] },
                kind = unit.kind.name,
                number = unit.number,
                title = unit.title,
                canonicalKey = canonicalKey(symbol, unit, seenKeys),
                ord = index,
                startOrd = unit.startOrd,
                endOrd = unit.endOrd,
                startPage = unit.startPage,
                endPage = unit.endPage,
            )
        }
    }

    private fun canonicalKey(
        symbol: String,
        unit: PendingUnit,
        seen: MutableMap<String, Int>,
    ): String {
        val sym = symbol.trim().lowercase().ifBlank { "doc" }
        val slug = normalizeText(unit.title)
            .replace(Regex("\\s+"), "-")
            .take(120)
            .ifBlank { "documento" }
        val base = "$sym:${unit.kind.name.lowercase()}:$slug"
        val occurrence = (seen[base] ?: 0) + 1
        seen[base] = occurrence
        return if (occurrence == 1) base else "$base#$occurrence"
    }

    private fun parseHeading(section: String): Heading? {
        val match = HEADING_RE.find(normalizeText(section)) ?: return null
        val kind = when (match.groupValues[1]) {
            "parte" -> PublicationUnitKind.PART
            "capitulo" -> PublicationUnitKind.CHAPTER
            "artigo" -> PublicationUnitKind.ARTICLE
            "licao" -> PublicationUnitKind.LESSON
            "apendice" -> PublicationUnitKind.APPENDIX
            "secao" -> PublicationUnitKind.SECTION
            else -> return null
        }
        return Heading(kind, match.groupValues[2].uppercase())
    }

    private fun fallbackKind(symbol: String, pubKind: String?): PublicationUnitKind {
        val sym = symbol.trim().lowercase()
        val kind = pubKind?.trim()?.lowercase()?.takeIf { it != "jwpub" }
            ?: PubCatalog.entryOf(sym)?.kind
        return when {
            kind == "magazine" ||
                sym in setOf("w", "wp", "g", "gn") ||
                MAGAZINE_RE.containsMatchIn(sym) -> PublicationUnitKind.ARTICLE

            sym == "it" || sym.startsWith("it-") || sym == "rsg" ->
                PublicationUnitKind.ARTICLE

            sym == "lmd" || sym == "th" -> PublicationUnitKind.LESSON

            kind == "workbook" || kind == "manual" ||
                sym == "s-38" || sym == "s38" -> PublicationUnitKind.SECTION

            kind == "book" || kind == "brochure" -> PublicationUnitKind.CHAPTER

            else -> PublicationUnitKind.DOCUMENT
        }
    }

    private fun cleanSection(raw: String): String =
        raw.trim().removePrefix("# ").trim().take(200)
}
