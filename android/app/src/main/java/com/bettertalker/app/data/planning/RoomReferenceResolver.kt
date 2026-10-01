package com.bettertalker.app.data.planning

import com.bettertalker.app.data.db.AttachmentDao
import com.bettertalker.app.data.db.PassageDao
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

    override suspend fun resolvePublication(ref: PublicationRef): ResolvedReference {
        // 1. Attachments indexados com symbol compatível (igualdade exata;
        // cobre "w21.08", "g 8/13" e "w94 1/8" — todos saem do detectSymbol).
        val matching = attachmentDao.all().filter { it.indexed && it.symbol == ref.symbol }
        if (matching.isEmpty()) {
            return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.MISSING_CORPUS)
        }

        // 2. Aproximação: parágrafo primeiro, depois página, depois símbolo.
        // page/paragraph não estão indexados — a query é textual (PARTIAL).
        val ids = matching.map { it.id }
        val queries = buildList {
            if (ref.paragraph != null) add("§${ref.paragraph}")
            if (ref.page != null) add("pág. ${ref.page}")
            add(ref.symbol.lowercase())
        }
        for (q in queries) {
            val hits = passageDao.searchLikeIn(ids, normalizeText(q), limit = 5)
            if (hits.isNotEmpty()) {
                val best = hits.first()
                return ResolvedReference(
                    original = ref.symbol,
                    canonicalRef = best.ref.ifBlank { null },
                    text = best.text,
                    passageId = best.id,
                    status = ReferenceStatus.PARTIAL,
                )
            }
        }

        return ResolvedReference(ref.symbol, null, null, null, ReferenceStatus.UNRESOLVED)
    }
}
