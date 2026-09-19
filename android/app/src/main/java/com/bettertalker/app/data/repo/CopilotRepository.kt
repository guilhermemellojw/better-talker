package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.buildJwUrl
import com.bettertalker.app.data.util.normalizeText

data class IdeaCard(
    val title: String,
    val body: String,
    val snippet: String,
    val jwUrl: String,
    val source: String = "" // ex: "Beneficie-se…" / "Melhore…" / "Nota"
)

data class ScopedHit(val passage: PassageEntity, val source: String)

class CopilotRepository(private val db: AppDatabase) {

    /** Slots base (be/th) prontos para consulta. */
    suspend fun missingBases(): List<String> {
        val missing = mutableListOf<String>()
        for (pub in BASE_PUBS) {
            if (db.attachmentDao().baseReady(pub.slot) == null) missing += pub.slot
        }
        return missing
    }

    suspend fun baseTitle(slot: String): String =
        BASE_PUBS.firstOrNull { it.slot == slot }?.title ?: slot

    /** Escopo: 2 bases + anexos vinculados à nota (se aberta). 100% offline. */
    suspend fun askScoped(query: String, noteId: String? = null, limit: Int = 6): List<ScopedHit> {
        val norm = normalizeText(query).split(" ")
            .filter { it.length > 2 }.take(3).joinToString(" ")
        if (norm.isBlank()) return emptyList()
        val keys = listOf(norm).plus(
            norm.split(" ").take(1)
        ).distinct()

        val scopeIds = mutableListOf<String>()
        val sourceOf = mutableMapOf<String, String>()
        for (pub in BASE_PUBS) {
            val ready = db.attachmentDao().baseReady(pub.slot)
            if (ready != null) {
                scopeIds += ready.id
                sourceOf[ready.id] = pub.title
            }
        }
        if (noteId != null) {
            val linked = db.attachmentDao().all().filter { it.noteId == noteId && it.indexed }
            for (a in linked) {
                if (!scopeIds.contains(a.id)) {
                    scopeIds += a.id
                    sourceOf[a.id] = "Nota"
                }
            }
        }
        if (scopeIds.isEmpty()) {
            // fallback: acervo inteiro (antes das bases existirem)
            val hits = searchFallback(norm, keys, limit)
            return hits.map { ScopedHit(it, "Biblioteca") }
        }
        val out = mutableListOf<ScopedHit>()
        for (k in keys) {
            val hits = db.passageDao().searchLikeIn(scopeIds, k, limit)
            for (h in hits) {
                if (out.none { it.passage.id == h.id }) {
                    out += ScopedHit(h, sourceOf[h.attachmentId] ?: "Biblioteca")
                }
                if (out.size >= limit) return out
            }
        }
        return out
    }

    private suspend fun searchFallback(norm: String, keys: List<String>, limit: Int): List<PassageEntity> {
        for (k in keys) {
            val hits = db.passageDao().searchLike(k, limit)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    suspend fun passagesFor(attachmentId: String) = db.passageDao().forAttachment(attachmentId)

    /** Gera ideias de discurso a partir dos trechos — sem inventar citação. */
    fun ideasFor(topic: String, hits: List<ScopedHit>): List<IdeaCard> {
        if (hits.isEmpty()) return emptyList()
        val t = { i: Int -> hits.getOrNull(i) ?: hits[0] }
        return listOf(
            IdeaCard(
                "Abertura — pergunta",
                "Abra com uma pergunta direta sobre “$topic” e uma pausa. Prenda atenção antes de explicar.",
                t(0).passage.text.take(140), buildJwUrl(topic), t(0).source
            ),
            IdeaCard(
                "Desenvolvimento — 2 pontos",
                "Divida em 2 pontos curtos, cada um com 1 exemplo prático. Fale com suas palavras.",
                t(1).passage.text.take(140), buildJwUrl(topic), t(1).source
            ),
            IdeaCard(
                "Conclusão — aplicação",
                "Termine com 1 aplicação prática para esta semana. Curto e direto.",
                t(2).passage.text.take(140), buildJwUrl(topic), t(2).source
            )
        )
    }

    fun summary(hits: List<ScopedHit>): String {
        if (hits.isEmpty()) return "Nenhum trecho local encontrado. Baixe as 2 publicações-base e tente de novo."
        return hits.take(3).mapIndexed { i, h -> "${i + 1}. [${h.source}] ${h.passage.text.take(160)}" }
            .joinToString("\n\n")
    }
}
