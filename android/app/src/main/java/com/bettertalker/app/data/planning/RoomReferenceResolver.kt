package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.PassageDao
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.data.util.RefDetector
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
            ?: return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)

        val slice = unitSlice(hit.id, needle, rawNeedle)
            ?: return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)

        // 3. Parágrafo: exato quando o índice tem numeração (T4); senão a
        //    unidade inteira (PARTIAL — texto real, recorte aproximado).
        val paragraph = ref.paragraph
        val exact = if (paragraph != null) slice.filter { it.paragraph == paragraph } else emptyList()
        val chosen = exact.ifEmpty { slice }
        val text = chosen.joinToString(" ") { it.text }.trim().take(MAX_UNIT_CHARS)
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
            passageId = chosen.first().id,
            status = if (paragraph != null && exact.isNotEmpty()) {
                ReferenceStatus.RESOLVED
            } else {
                ReferenceStatus.PARTIAL
            },
        )
    }

    /**
     * Fatia da unidade no anexo: seção indexada (lição/capítulo/estudo), a
     * passagem-título do JWPUB ("# Título") que contém o alvo — fatiada até
     * o próximo título — ou o título exato. Funciona com o índice atual
     * (sem reindexação).
     */
    private suspend fun unitSlice(
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
    private fun sectionNeedles(raw: String): List<String> {
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

    private companion object {
        /** Teto de trechos por unidade citada (artigo/lição). */
        const val UNIT_PASSAGES = 200

        /** Candidatos da busca textual por unidade. */
        const val UNIT_CANDIDATES = 400

        /** Teto de texto devolvido (o prompt trunca de novo). */
        const val MAX_UNIT_CHARS = 4000
    }
}
