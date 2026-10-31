package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.bestContextMatch
import com.bettertalker.app.data.util.bestContextWindow
import com.bettertalker.app.data.util.contextTerms
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.ReferenceResolver
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.planning.ResolvedReference

/**
 * Implementação de [ReferenceResolver] sobre o acervo local (Room).
 *
 * DÉBITOS (3.5a):
 * - Bible: `findByRef` retorna LIMIT 1; múltiplas TNMs → pega a primeira.
 *   Melhoria: scoping por attachment preferido (usuário escolhe).
 * - Publicações: page/paragraph não indexados; match aproximado por
 *   `searchLikeIn`. Status sempre PARTIAL, nunca RESOLVED. Melhoria:
 *   extrair page/paragraph no IndexPublicationWorker (requer reindex).
 * - Sem fallback fantasma: se [normalizeBibleBookName] falha → MISSING_CORPUS
 *   (nunca inventa canonicalRef a partir da chave interna do detector).
 */
class RoomReferenceResolver(
    private val passageDao: PassageDao,
    private val attachmentDao: AttachmentDao,
) : ReferenceResolver {

    override suspend fun resolveBible(ref: String): ResolvedReference {
        // 1. Extrai (livro, capítulo, versículo) do texto livre.
        val bibleRef = RefDetector.detectBible(ref).firstOrNull()
            ?: return ResolvedReference(ref, null, null, null, ReferenceStatus.MISSING_CORPUS)

        // 2. Normaliza o rótulo para a abreviação do índice TNM.
        // Sem fallback fantasma: null aqui significa formato desconhecido.
        val abbrev = normalizeBibleBookName(bibleRef.label)
            ?: return ResolvedReference(ref, null, null, null, ReferenceStatus.MISSING_CORPUS)

        // 3. Monta a ref canônica no formato do índice ("Gên 3:6").
        val canonical = "$abbrev ${bibleRef.chapter}:${bibleRef.verse}"

        // 4. Busca exata (LIMIT 1).
        val passage = passageDao.findByRef(canonical)
        return if (passage != null) {
            ResolvedReference(ref, canonical, passage.text, passage.id, ReferenceStatus.RESOLVED)
        } else {
            ResolvedReference(ref, canonical, null, null, ReferenceStatus.UNRESOLVED)
        }
    }

    /**
     * T3 — resolve a publicação no acervo REAL: casa o anexo como a UI
     * (`matchEdition`: nome do arquivo/tokens/slot; it-1/it-2 → it) e
     * transcreve a UNIDADE citada — artigo ("it "Gedalias" n.° 4") ou
     * lição/capítulo ("lmd lição 3 § 4").
     *
     * Sem unidade identificável (ex.: só página "it 813") → UNRESOLVED:
     * nunca devolve trecho aleatório (o antigo fallback textual por "§N"/
     * símbolo virava lixo). Com parágrafo indexado (T4) → RESOLVED exato;
     * senão → PARTIAL com o texto real da unidade.
     */
    override suspend fun resolvePublication(ref: PublicationRef): ResolvedReference {
        // 1. Edição: a coluna `symbol` fica vazia em quase todo o acervo —
        //    casa pelo mesmo caminho da UI (arquivo/tokens/baseSlot).
        val editionRef = RefDetector.detect(ref.symbol).firstOrNull()
            ?: RefDetector.DetectedRef(
                raw = ref.symbol,
                kind = RefDetector.Kind.BOOK,
                pubKey = ref.symbol,
                editionKey = "book|${ref.symbol}",
                label = ref.symbol,
            )
        val hit = RefDetector.matchEdition(editionRef, attachmentDao.all())
            ?: return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.MISSING_CORPUS)
        if (!hit.indexed) {
            return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)
        }

        // 2. Unidade citada (artigo ou lição/capítulo/estudo).
        val rawNeedle = listOfNotNull(ref.article, ref.chapter)
            .firstOrNull { it.isNotBlank() }
        val needle = rawNeedle
            ?.let { normalizeText(it) }
            ?.takeIf { it.length >= 3 }
        if (needle == null) {
            // RC1: página + parágrafo SEM artigo/capítulo (ex.: "w25.07 22
            // §§ 8-9") — resolve direto pelo índice T4 (page/paragraph).
            return resolveByPageParagraph(ref, hit)
        }

        val slice = unitSliceOf(passageDao, hit.id, needle, rawNeedle)
            ?: return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)

        // 3. Parágrafo: exato quando o índice tem numeração (T4); senão a
        //    unidade inteira (PARTIAL — texto real, recorte aproximado).
        val paragraph = ref.paragraph
        val exact = if (paragraph != null) {
            slice.filter {
                it.paragraph == paragraph ||
                    (it.paragraph == null &&
                        RefDetector.leadingParagraphNumber(it.text) == paragraph)
            }
        } else emptyList()
        val chosen = exact.ifEmpty { slice }
        // C2.8: se o § exato não está indexado e a unidade é maior que o
        // teto, foca no trecho citado (contexto do esboço ou § inicial) em
        // vez de mandar só o começo do artigo/lição.
        val focused = if (exact.isEmpty() && paragraph != null &&
            chosen.joinToString(" ").length > MAX_UNIT_CHARS
        ) {
            ref.context?.takeIf { it.isNotBlank() }?.let { focusByContext(chosen, it) }
                ?: focusByParagraph(chosen, paragraph, ref.paragraphEnd ?: paragraph)
        } else {
            null
        }
        val finalRows = focused ?: chosen
        val text = if (focused == null && chosen.joinToString(" ").length > MAX_UNIT_CHARS) {
            headTailText(chosen)
        } else {
            finalRows.joinToString(" ") { it.text }.trim().take(MAX_UNIT_CHARS)
        }
        if (text.isBlank()) {
            return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)
        }
        val label = buildString {
            append(ref.symbol)
            ref.article?.takeIf { it.isNotBlank() }?.let { append(" “$it”") }
            ref.chapter?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            paragraph?.let { append(" §$it") }
        }
        return ResolvedReference(
            original = ref.symbol,
            canonicalRef = label,
            text = text,
            passageId = finalRows.first().id,
            status = if (paragraph != null && exact.isNotEmpty()) {
                ReferenceStatus.RESOLVED
            } else {
                ReferenceStatus.PARTIAL
            },
        )
    }

    /**
     * C2.6 — resolve citação por unidade sem artigo/capítulo:
     * 1. página + § indexados → RESOLVED;
     * 2. página/faixa sem § → PARTIAL com o texto real da página;
     * 3. § sem página → PARTIAL via byParagraphRange;
     * 4. sem página/§ indexados → contexto do esboço acima do threshold;
     * 5. nada → UNRESOLVED (nunca trecho aleatório).
     */
    private suspend fun resolveByPageParagraph(
        ref: PublicationRef,
        hit: AttachmentEntity,
    ): ResolvedReference {
        val page = ref.page
        // C2.8: "142-4" é 142..144, não 142..4 (o parser já expande, mas
        // refs vindas de JSON/chat também passam por aqui).
        val pageEnd = RefDetector.fullPageEnd(page, ref.pageEnd)
            ?.takeIf { p -> page != null && p > page }
        val start = ref.paragraph
        val end = ref.paragraphEnd?.takeIf { p -> start != null && p > start } ?: start
        val base = baseSymbolOf(ref.symbol)

        if (page != null && start != null) {
            val rows = (page..(pageEnd ?: page)).flatMap { p ->
                passageDao.byPageParagraph(hit.id, p, start, end ?: start)
            }.distinctBy { it.id }
            if (rows.isNotEmpty()) {
                return resolved(
                    ref = ref,
                    label = pageParagraphLabel(base, page, pageEnd, start, end),
                    rows = rows,
                    status = ReferenceStatus.RESOLVED,
                )
            }
        }

        val pageRows = if (page != null) {
            passageDao.byPage(hit.id, page, pageEnd ?: page, MAX_CONTEXT_PASSAGES)
        } else {
            emptyList()
        }
        // A edição tem índice de página? Só nesse caso o § global é
        // perigoso: ele pode pegar o mesmo § de outro artigo. Se a edição
        // não tem página nenhuma (índice antigo), o § é o melhor localizador.
        val hasPageIndex = pageRows.isNotEmpty() ||
            (page != null &&
                passageDao.byPage(hit.id, 0, Int.MAX_VALUE, 1).isNotEmpty())

        // Página sem § → PARTIAL. Página com § só cai aqui quando o índice
        // antigo NÃO tem números de parágrafo (todas as linhas com
        // paragraph=null): aí o texto da página é a melhor leitura real.
        // Se há §§ indexados e nenhum casa, não devolve a página errada.
        if (page != null && pageRows.isNotEmpty()) {
            val pageUsable = start == null || pageRows.all { it.paragraph == null }
            if (pageUsable) {
                val focused = focusPageRows(pageRows, start, end, ref.context)
                return resolved(
                    ref = ref,
                    label = pageLabel(base, page, pageEnd),
                    rows = focused,
                    status = ReferenceStatus.PARTIAL,
                    // Faixa de várias páginas sem foco por §/contexto:
                    // começo + fim, para não perder o trecho citado no fim.
                    headTail = pageEnd != null && pageEnd > page &&
                        focused.size == pageRows.size,
                )
            }
        }

        // § sem página (ou índice antigo sem página): único caso em que o
        // fallback global de § é seguro. Com página indexada, devolver o §
        // de outro artigo é pior do que não devolver nada.
        if (start != null && (page == null || !hasPageIndex)) {
            val rows = passageDao.byParagraphRange(
                hit.id,
                start,
                end ?: start,
                MAX_CONTEXT_PASSAGES,
            )
            if (rows.isNotEmpty()) {
                return resolved(
                    ref = ref,
                    label = paragraphLabel(base, start, end),
                    rows = rows,
                    status = ReferenceStatus.PARTIAL,
                )
            }
        }

        // PDF/EPUB sem página/§ indexados: usa a frase do esboço. Com página
        // indexada, o contexto não substitui a página citada (evita trocar
        // um artigo errado por outro).
        val context = ref.context?.takeIf { it.isNotBlank() }
        if (context != null && (page == null || !hasPageIndex)) {
            val candidates = contextPassages(
                passageDao,
                hit.id,
                context,
                MAX_CONTEXT_PASSAGES,
            )
            val match = bestContextMatch(candidates.map { it.text }, context)
            if (match != null) {
                val center = candidates[match.index]
                val window = passageDao.betweenOrd(
                    hit.id,
                    (center.ord - 2).coerceAtLeast(0),
                    center.ord + 2,
                    MAX_CONTEXT_PASSAGES,
                ).ifEmpty { listOf(center) }
                return resolved(
                    ref = ref,
                    label = "$base — contexto do esboço",
                    rows = window,
                    status = ReferenceStatus.PARTIAL,
                )
            }
        }

        return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)
    }

    /**
     * C2.8 — foca a página no trecho citado:
     * 1. se a página tem §§ como número inicial, recorta §start–§end;
     * 2. senão, se a frase do esboço casa uma janela de 3 trechos, usa-a;
     * 3. senão, devolve a página inteira (comportamento antigo).
     */
    private fun focusPageRows(
        rows: List<PassageEntity>,
        start: Int?,
        end: Int?,
        context: String?,
    ): List<PassageEntity> {
        if (start != null) {
            focusByParagraph(rows, start, end ?: start)?.let { return it }
        }
        val ctx = context?.takeIf { it.isNotBlank() }
        if (ctx != null) {
            focusByContext(rows, ctx)?.let { return it }
        }
        return rows
    }

    private fun focusByParagraph(
        rows: List<PassageEntity>,
        start: Int,
        end: Int,
    ): List<PassageEntity>? {
        if (rows.isEmpty()) return null
        val numbered = rows.mapIndexedNotNull { index, passage ->
            val n = passage.paragraph ?: RefDetector.leadingParagraphNumber(passage.text)
            if (n == null) null else index to n
        }
        if (numbered.isEmpty()) return null
        val first = numbered.firstOrNull { (_, n) -> n >= start }?.first ?: return null
        val last = numbered.lastOrNull { (_, n) -> n <= end }?.first ?: return null
        if (last < first) return null
        // Vai até o próximo § fora do range (exclusivo): inclui todo o
        // conteúdo do último § citado, não só o título dele.
        val next = numbered.firstOrNull { (index, n) -> index > last && n > end }?.first
        val from = (first - 1).coerceAtLeast(0)
        val to = ((next ?: rows.size) - 1).coerceAtMost(rows.lastIndex)
        return rows.subList(from, to + 1)
    }

    private fun focusByContext(
        rows: List<PassageEntity>,
        context: String,
    ): List<PassageEntity>? {
        if (rows.size < 3) return null
        val match = bestContextWindow(rows.map { it.text }, context, window = 3)
            ?: return null
        val from = (match.index - 2).coerceAtLeast(0)
        val to = (match.index + 4).coerceAtMost(rows.lastIndex)
        return rows.subList(from, to + 1)
    }

    private fun resolved(
        ref: PublicationRef,
        label: String,
        rows: List<PassageEntity>,
        status: ReferenceStatus,
        headTail: Boolean = false,
    ): ResolvedReference {
        val text = if (headTail) {
            headTailText(rows)
        } else {
            rows.joinToString(" ") { it.text }.trim().take(MAX_UNIT_CHARS)
        }
        if (text.isBlank()) {
            return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)
        }
        return ResolvedReference(
            original = ref.symbol,
            canonicalRef = label,
            text = text,
            passageId = rows.first().id,
            status = status,
        )
    }

    /**
     * C2.8 — faixas de página maiores que o teto: em vez de mandar só o
     * começo (e perder o fim do artigo), manda começo + fim. O trecho
     * citado pode estar em qualquer ponto da faixa; o modelo recebe as
     * duas bordas e o meio fica no contexto.
     */
    private fun headTailText(rows: List<PassageEntity>): String {
        val full = rows.joinToString(" ") { it.text }.trim()
        if (full.length <= MAX_UNIT_CHARS) return full
        val half = (MAX_UNIT_CHARS - 3) / 2
        return full.take(half).trimEnd() + " … " + full.takeLast(half).trimStart()
    }

    private fun baseSymbolOf(symbol: String): String = symbol
        .replace(
            Regex(
                """\s+(?:(?:p[áa]g(?:ina)?s?|pg|p)\.?\s*)?""" +
                    """\d{1,3}(?:\s*[-–]\s*\d{1,3})?""" +
                    """(?:\s*(?:§{1,2}|par[áa]g(?:rafo)?s?\.?)\s*\d{1,3}""" +
                    """(?:\s*[-–]\s*\d{1,3})?)?$""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        .trim()
        .ifBlank { symbol }

    private fun pageLabel(base: String, page: Int, pageEnd: Int?): String = buildString {
        append(base).append(" pág. ").append(page)
        pageEnd?.let { append('-').append(it) }
    }

    private fun paragraphLabel(base: String, start: Int, end: Int?): String = buildString {
        append(base).append(" §").append(start)
        end?.takeIf { it > start }?.let { append('-').append(it) }
    }

    private fun pageParagraphLabel(
        base: String,
        page: Int,
        pageEnd: Int?,
        start: Int,
        end: Int?,
    ): String = pageLabel(base, page, pageEnd) +
        paragraphLabel("", start, end)

    private companion object {
        /** Teto de passagens por página/contexto (o prompt trunca de novo). */
        const val MAX_CONTEXT_PASSAGES = 80
    }
}

/**
 * C2.6 — candidatos para o fallback por contexto: busca os termos mais
 * longos da frase do esboço no índice da edição. Se a busca não retornar
 * nada (PDF sem texto normalizado?), cai nas primeiras passagens.
 */
internal suspend fun contextPassages(
    passageDao: PassageDao,
    attachmentId: String,
    context: String,
    limit: Int = 120,
): List<PassageEntity> {
    val terms = contextTerms(context)
        .sortedByDescending { it.length }
        .take(6)
    val hits = terms
        .flatMap { term -> passageDao.searchLikeIn(listOf(attachmentId), term, 80) }
        .distinctBy { it.id }
    return if (hits.isNotEmpty()) {
        hits.take(limit)
    } else {
        passageDao.forAttachment(attachmentId).take(limit)
    }
}

/** Teto de trechos por unidade citada (artigo/lição). */
internal const val UNIT_PASSAGES = 200

/** Candidatos da busca textual por unidade. */
internal const val UNIT_CANDIDATES = 400

/** Teto de texto devolvido (o prompt trunca de novo). */
internal const val MAX_UNIT_CHARS = 4000

/**
 * T3/T6 — fatia da unidade citada (artigo/lição) no acervo: seção indexada
 * (variantes de palavra cheia), passagem-título do JWPUB ("# Título") até o
 * próximo título, ou título exato. Compartilhada com o chat (verificação de
 * conteúdo) — antes o chat carregava o anexo inteiro (LIMIT 500) e dava
 * falso aviso "sem fonte".
 */
internal suspend fun unitSliceOf(
    passageDao: PassageDao,
    attachmentId: String,
    needleNorm: String,
    rawNeedle: String,
): List<PassageEntity>? {
    // 1. Seção indexada (pós-T4: "# Título" e capítulos por extenso).
    for (n in sectionNeedles(rawNeedle)) {
        val bySection = passageDao.bySection(attachmentId, n, UNIT_PASSAGES)
        if (bySection.isNotEmpty()) return bySection
    }
    // 2. Passagem-título ("# Título") contendo o alvo.
    val candidates = passageDao.searchLikeIn(listOf(attachmentId), needleNorm, UNIT_CANDIDATES)
    if (candidates.isEmpty()) return null
    val title = candidates.firstOrNull {
        it.text.startsWith("# ") &&
            normalizeText(it.text.removePrefix("# ")).contains(needleNorm)
    }
    if (title != null) {
        val end = passageDao.nextTitleAfter(attachmentId, title.ord)?.ord ?: Int.MAX_VALUE
        val slice = passageDao.betweenOrd(attachmentId, title.ord, end - 1, UNIT_PASSAGES)
        if (slice.isNotEmpty()) return slice
    }
    val bySection = candidates.filter {
        it.section.isNotBlank() && normalizeText(it.section).contains(needleNorm)
    }
    if (bySection.isNotEmpty()) return bySection.sortedBy { it.ord }.take(UNIT_PASSAGES)
    return candidates.firstOrNull { normalizeText(it.text) == needleNorm }?.let { listOf(it) }
}

/**
 * T4: variantes do alvo para casar a abreviação com o rótulo indexado
 * ("cap. 2" → "Capítulo 2"; "lição 3" mantém).
 */
internal fun sectionNeedles(raw: String): List<String> {
    val out = mutableListOf(raw)
    val m = Regex(
        """^\s*(cap\.?|cap[íi]tulo|li[cç][aã]o|estudo)\s*(\d{1,3})\s*$""",
        RegexOption.IGNORE_CASE
    ).find(raw)
    if (m != null) {
        val n = m.groupValues[2]
        val full = when {
            m.groupValues[1].lowercase().startsWith("cap") -> "Capítulo"
            m.groupValues[1].lowercase().startsWith("li") -> "Lição"
            else -> "Estudo"
        }
        out += "$full $n"
    }
    return out.distinct()
}
