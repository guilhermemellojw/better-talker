package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.buildJwUrl
import com.bettertalker.app.data.util.normalizeText

data class IdeaCard(
    val title: String,
    val body: String,
    val snippet: String,
    val jwUrl: String,
    val source: String = "", // ex: "Beneficie-se…" / "Melhore…" / "Nota"
    /** Seção do esboço a que pertence (destino sugerido de inserção). */
    val sectionTitle: String = "",
    val placementReason: String = ""
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
    suspend fun askScoped(query: String, noteId: String? = null, limit: Int = 6, maxWords: Int = 4): List<ScopedHit> {
        val words = normalizeText(query).split(" ")
            .filter { it.length > 2 }.take(maxWords)
        if (words.isEmpty()) return emptyList()

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
        // consulta cada palavra e ordena por nº de acertos (ranking simples)
        suspend fun ranked(ids: List<String>?, label: (String) -> String): List<ScopedHit> {
            val hits = mutableMapOf<String, ScopedHit>()
            val score = mutableMapOf<String, Int>()
            for (w in words) {
                val found = if (ids == null) db.passageDao().searchLike(w, limit * 2)
                else db.passageDao().searchLikeIn(ids, w, limit * 2)
                for (h in found) {
                    if (!hits.containsKey(h.id)) {
                        val base = label(h.attachmentId)
                        val src = if (h.section.isNotEmpty()) "$base · ${h.section}" else base
                        hits[h.id] = ScopedHit(h, src)
                    }
                    score[h.id] = (score[h.id] ?: 0) + 1
                }
            }
            return hits.values.sortedByDescending { score[it.passage.id] ?: 0 }.take(limit)
        }
        if (scopeIds.isEmpty()) {
            // fallback: acervo inteiro (antes das bases existirem)
            return ranked(null) { "Biblioteca" }
        }
        return ranked(scopeIds) { sourceOf[it] ?: "Biblioteca" }
    }

    suspend fun passagesFor(attachmentId: String) = db.passageDao().forAttachment(attachmentId)

    suspend fun hasOutline(noteId: String?): Boolean =
        noteId != null && db.outlineDao().getForNote(noteId) != null

    /**
     * Ideias para UMA seção do esboço (geração avulsa).
     * A seção guia: consulta = título da seção + pergunta; vizinhas dão contexto.
     */
    suspend fun ideasForSection(
        section: com.bettertalker.app.data.util.OutlineSection,
        neighbors: List<String>,
        query: String,
        noteId: String?
    ): List<IdeaCard> {
        // o corpo da seção guia a busca: subtemas e refs do esboço original
        val q = listOf(section.title, section.body.take(400), query)
            .filter { it.isNotBlank() }.joinToString(" ")
        val hits = askScoped(q.ifBlank { section.title }, noteId, 6, maxWords = 8)
        if (hits.isEmpty()) return emptyList()
        val t = { i: Int -> hits.getOrNull(i) ?: hits[0] }
        val time = section.minutes?.let { " (${it} min)" } ?: ""
        val link = if (neighbors.isNotEmpty()) " Liga com: ${neighbors.joinToString(" → ")}." else ""
        return listOf(
            IdeaCard(
                title = "Explorar — ${section.title}",
                body = "Ângulo de abordagem para “${section.title}”$time.$link " +
                    "Abra a seção com uma pergunta ou cena que prenda atenção.",
                snippet = t(0).passage.text.take(140),
                jwUrl = buildJwUrl(section.title),
                source = t(0).source,
                sectionTitle = section.title,
                placementReason = "Pertence à seção “${section.title}” do esboço."
            ),
            IdeaCard(
                title = "Ilustrar e aplicar — ${section.title}",
                body = "Ilustração ou aplicação prática para “${section.title}”$time. " +
                    "Feche a seção ligando ao próximo ponto.",
                snippet = t(1).passage.text.take(140),
                jwUrl = buildJwUrl(section.title),
                source = t(1).source,
                sectionTitle = section.title,
                placementReason = "Pertence à seção “${section.title}” do esboço."
            )
        )
    }

    /** Referências da nota: detecta, casa edição exata com anexos locais. */
    suspend fun checkRefs(noteText: String): List<RefDetector.RefStatus> =
        RefDetector.checkAll(noteText, db.attachmentDao().all())

    /** Referências citadas no esboço (guardadas no link): resolve contra anexos atuais. */
    suspend fun outlineRefs(refsJson: String): List<RefDetector.RefStatus> =
        RefDetector.resolve(RefDetector.detectedFromJson(refsJson), db.attachmentDao().all())

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
