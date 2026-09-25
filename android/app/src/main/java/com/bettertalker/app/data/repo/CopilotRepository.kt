package com.bettertalker.app.data.repo

import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.STOPWORDS_PT
import com.bettertalker.app.data.util.buildJwUrl
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.splitRawSentences

data class IdeaCard(
    val title: String,
    val body: String,
    val snippet: String,
    val jwUrl: String,
    val source: String = "", // ex: "Beneficie-se…" / "Melhore…" / "Nota"
    /** Seção do esboço a que pertence (destino sugerido de inserção). */
    val sectionTitle: String = "",
    val placementReason: String = "",
    /** false = guia de estrutura (orientação be/th): nunca vai para a nota. */
    val insertable: Boolean = true,
    /** Fase 8: categoria de treinamento do guia (ex: illustration); null = n/a. */
    val trainingCategory: String? = null
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
    suspend fun askScoped(
        query: String,
        noteId: String? = null,
        limit: Int = 6,
        maxWords: Int = 4,
        /** anexos das publicações citadas (id -> rótulo), mesmo sem vínculo */
        extraIds: List<String> = emptyList(),
        extraLabels: Map<String, String> = emptyMap(),
        /** bônus de ranking por palavra (ex: título ×3 via +2) */
        boost: Map<String, Int> = emptyMap()
    ): List<ScopedHit> {
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
        // citadas na nota/esboço (baixadas, mesmo sem vínculo): entram com o título real
        for (id in extraIds) {
            if (!scopeIds.contains(id)) {
                scopeIds += id
                sourceOf[id] = extraLabels[id] ?: "Biblioteca"
            }
        }
        // consulta cada palavra e ordena por nº de acertos (ranking simples).
        // boost soma pontos extras por palavra (título ×3, corpo ×2, extras ×1).
        suspend fun ranked(ids: List<String>, label: (String) -> String): List<ScopedHit> {
            val hits = mutableMapOf<String, ScopedHit>()
            val score = mutableMapOf<String, Int>()
            for (w in words) {
                val found = db.passageDao().searchLikeIn(ids, w, limit * 2)
                for (h in found) {
                    if (!hits.containsKey(h.id)) {
                        val base = label(h.attachmentId)
                        val src = if (h.section.isNotEmpty()) "$base · ${h.section}" else base
                        hits[h.id] = ScopedHit(h, src)
                    }
                    score[h.id] = (score[h.id] ?: 0) + 1 + (boost[w] ?: 0)
                }
            }
            return hits.values.sortedByDescending { score[it.passage.id] ?: 0 }.take(limit)
        }
        if (scopeIds.isEmpty()) {
            // Fase 8 (§10): sem fontes autorizadas => insuficiência.
            // NUNCA varrer a biblioteca inteira como fallback silencioso.
            return emptyList()
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
        noteId: String?,
        extraIds: List<String> = emptyList(),
        extraLabels: Map<String, String> = emptyMap(),
        /** títulos de subseções filhas: entram na busca, não só no texto */
        subtopics: List<String> = emptyList()
    ): List<IdeaCard> {
        // o corpo da seção guia a busca: subtemas e refs do esboço original.
        // queryTerms garante que os subtópicos cheguem à busca (sem stopwords,
        // sem repetidos, título primeiro) em vez de morrer no corte de palavras.
        val q = queryTerms(
            section.title,
            section.body.take(800),
            (subtopics + query).joinToString(" "),
            12
        ).joinToString(" ")
        val boost = fieldBoost(
            section.title,
            section.body.take(800),
            (subtopics + query).joinToString(" ")
        )
        val hits = askScoped(q.ifBlank { section.title }, noteId, 6, maxWords = 12, extraIds, extraLabels, boost)
        // SÓ conteúdo (citadas): be/th instruem a estrutura, nunca são matéria.
        // rerank aproxima o que fala a língua da seção (título/corpo/subtópicos).
        val refWords = queryTerms(section.title, section.body.take(800), subtopics.joinToString(" "), 24).toSet()
        val content = rerankByOverlap(contentHits(hits), refWords)
        if (content.isEmpty()) return emptyList()
        val t = { i: Int -> content.getOrNull(i) ?: content[0] }
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

    /** Tipos de exemplo prático, seguindo as instruções das publicações. */
    enum class ExampleKind { INTRO, ILLUSTRATION, CONCLUSION, QUESTION }

    /** Busca fixa da orientação de cada tipo (be/th como guia de estrutura). */
    fun guideQuery(kind: ExampleKind): String = exampleGuideQuery(kind)

    /**
     * Tipo pedido pelas palavras (normalizadas); sem pista, vale a posição
     * (1ª seção -> introdução, última -> conclusão, meio -> ilustração).
     */
    fun kindFor(isFirst: Boolean, isLast: Boolean, normalizedWords: String): ExampleKind =
        exampleKindFor(isFirst, isLast, normalizedWords)

    /**
     * Exemplo prático para UMA seção: modelo adaptável + orientação citada
     * (lição) + trecho de apoio. be/th instruem a estrutura; o conteúdo
     * vem das publicações mencionadas.
     */
    suspend fun exampleForSection(
        section: com.bettertalker.app.data.util.OutlineSection,
        isFirst: Boolean,
        isLast: Boolean,
        kind: ExampleKind?,
        neighbors: List<String>,
        noteId: String?,
        extraIds: List<String> = emptyList(),
        extraLabels: Map<String, String> = emptyMap(),
        /** títulos de subseções filhas: entram na busca, não só no texto */
        subtopics: List<String> = emptyList()
    ): List<IdeaCard> {
        val q = queryTerms(
            section.title,
            section.body.take(800),
            subtopics.joinToString(" "),
            12
        ).joinToString(" ")
        val boost = fieldBoost(
            section.title,
            section.body.take(800),
            subtopics.joinToString(" ")
        )
        val topical = askScoped(q.ifBlank { section.title }, noteId, 6, maxWords = 12, extraIds, extraLabels, boost)
        // SÓ conteúdo (citadas): be/th instruem a estrutura, nunca são matéria.
        // rerank aproxima o que fala a língua da seção (título/corpo/subtópicos).
        val refWords = queryTerms(section.title, section.body.take(800), subtopics.joinToString(" "), 24).toSet()
        val content = rerankByOverlap(contentHits(topical), refWords)
        if (content.isEmpty()) return emptyList()
        val k = kind ?: kindFor(isFirst, isLast, "")
        val guide = askScoped(guideQuery(k), noteId, 3, extraIds = extraIds, extraLabels = extraLabels)
        val t = { i: Int -> content.getOrNull(i) ?: content[0] }
        val g = guide.firstOrNull()
        val time = section.minutes?.let { " (${it} min)" } ?: ""
        val link = if (neighbors.isNotEmpty()) " Liga com: ${neighbors.joinToString(" → ")}." else ""
        val kindLabel = when (k) {
            ExampleKind.INTRO -> "introdução"
            ExampleKind.ILLUSTRATION -> "ilustração"
            ExampleKind.CONCLUSION -> "conclusão"
            ExampleKind.QUESTION -> "pergunta inicial"
        }
        val modelBody = buildString {
            append("Rascunho a partir dos trechos — adapte com suas palavras.\n\n")
            when (k) {
                ExampleKind.INTRO -> {
                    append("“${section.title}”$time chama a nossa atenção. ")
                    append("Por que esse assunto é importante? O que precisamos fazer a respeito? ")
                    append("Vamos começar vendo o que a matéria diz:")
                }
                ExampleKind.ILLUSTRATION -> {
                    append("Para ilustrar “${section.title}”$time, use uma cena do dia a dia ligada a estes pontos:")
                }
                ExampleKind.CONCLUSION -> {
                    append("Para fechar “${section.title}”$time, recapitule e aplique:")
                }
                ExampleKind.QUESTION -> {
                    append("Pergunta inicial para “${section.title}”$time — ex.: «...?» — depois desenvolva com:")
                }
            }
            val quotes = composeDraft(content)
            if (quotes.isNotEmpty()) {
                append("\n\n")
                append(quotes.joinToString("\n\n") { "“${it.text}” [${it.source}]" })
            }
            when (k) {
                ExampleKind.INTRO -> append("\n\nFeche a abertura ligando à primeira ideia.$link")
                ExampleKind.ILLUSTRATION -> append("\n\nAplique em 1 frase e feche ligando ao próximo ponto.$link")
                ExampleKind.CONCLUSION -> append("\n\nTermine com 1 aplicação prática para esta semana. Curto e direto.")
                ExampleKind.QUESTION -> append("\n\nAguarde a resposta da assistência e ligue ao tema.$link")
            }
        }
        val cards = mutableListOf(
            IdeaCard(
                title = "Exemplo — $kindLabel de “${section.title}”",
                body = modelBody,
                snippet = t(0).passage.text.take(140),
                jwUrl = buildJwUrl(section.title),
                source = t(0).source,
                sectionTitle = section.title,
                placementReason = "Modelo pronto para a seção “${section.title}”."
            )
        )
        if (g != null) {
            // Fase 8: categoria efetiva (gravada ou classificada) vai ao card.
            val guideCategory = com.bettertalker.app.data.domain.TrainingClassifier.effective(
                g.passage.trainingCategory, g.passage.section, g.source, g.passage.text
            ).serial
            cards += IdeaCard(
                title = "Orientação — ${g.source}",
                body = "Siga a orientação de ${g.source} para $kindLabel. " +
                    "(Guia de estrutura — o texto da publicação não vai para o discurso.)",
                snippet = "",
                jwUrl = "",
                source = g.source,
                sectionTitle = section.title,
                placementReason = "Instrução da publicação sobre $kindLabel.",
                insertable = false,
                trainingCategory = guideCategory
            )
        }
        cards += IdeaCard(
            title = "Apoio — ${section.title}",
            body = t(1).passage.text.take(200),
            snippet = "",
            jwUrl = buildJwUrl(section.title),
            source = t(1).source,
            sectionTitle = section.title,
            placementReason = "Trecho de apoio para a seção “${section.title}”."
        )
        return cards
    }

    /** Referências da nota: detecta, casa edição exata com anexos locais. */
    suspend fun checkRefs(noteText: String): List<RefDetector.RefStatus> =
        RefDetector.checkAll(noteText, db.attachmentDao().all())

    /** Resolve lista já detectada (nota ∪ esboço) contra os anexos atuais. */
    suspend fun checkRefsList(refs: List<RefDetector.DetectedRef>): List<RefDetector.RefStatus> =
        RefDetector.resolve(refs, db.attachmentDao().all())

    /** Referências citadas no esboço (guardadas no link): resolve contra anexos atuais. */
    suspend fun outlineRefs(refsJson: String): List<RefDetector.RefStatus> =
        RefDetector.resolve(RefDetector.detectedFromJson(refsJson), db.attachmentDao().all())

    /**
     * Texto exato da referência capitulada ("lff cap. 5"): casa o anexo e
     * filtra pelo capítulo/lição da seção indexada. Vazio se não há match.
     */
    suspend fun refPassages(ref: RefDetector.DetectedRef, limit: Int = 2): List<ScopedHit> {
        val ch = RefDetector.chapterOf(ref.raw) ?: return emptyList()
        val hit = RefDetector.matchEdition(ref, db.attachmentDao().all()) ?: return emptyList()
        val label = when (ref.kind) {
            RefDetector.Kind.BOOK ->
                com.bettertalker.app.data.util.PubCatalog.titleOf(ref.pubKey) ?: ref.label
            RefDetector.Kind.MAGAZINE -> ref.label
        }
        return filterByChapter(db.passageDao().forAttachment(hit.id), ch.kind, ch.number)
            .take(limit).map { ScopedHit(it, label) }
    }

    /**
     * Trechos do versículo na TNM indexada (best-effort: depende do formato
     * extraído). Vazio se a TNM não está baixada/indexada.
     */
    suspend fun biblePassages(
        bookNorm: String,
        chapter: Int,
        verse: Int,
        limit: Int = 2
    ): List<ScopedHit> {
        val nwt = db.attachmentDao().baseReady("nwt") ?: return emptyList()
        val found = db.passageDao().searchLikeIn(listOf(nwt.id), "$bookNorm $chapter", limit * 4)
        val label = baseTitle("nwt")
        return found.filter { verseRefMatches(it.normalized, bookNorm, chapter, verse) }
            .take(limit).map { ScopedHit(it, label) }
    }

    /**
     * Anexos das publicações citadas (nota + esboço), mesmo sem vínculo à nota.
     * Retorna id -> rótulo (título real). be/th entram como guia, não conteúdo.
     */
    suspend fun refScopeIds(noteText: String, outlineRefsJson: String): Map<String, String> {
        val detected = RefDetector.detect(noteText) +
            RefDetector.detectedFromJson(outlineRefsJson)
        val all = db.attachmentDao().all()
        val out = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        for (ref in detected) {
            if (!seen.add(ref.editionKey)) continue
            val hit = RefDetector.matchEdition(ref, all) ?: continue
            out.getOrPut(hit.id) {
                when (ref.kind) {
                    RefDetector.Kind.BOOK ->
                        com.bettertalker.app.data.util.PubCatalog.titleOf(ref.pubKey) ?: ref.label
                    RefDetector.Kind.MAGAZINE -> ref.label
                }
            }
        }
        return out
    }

    /** Gera ideias de discurso a partir dos trechos — sem inventar citação. */
    fun ideasFor(topic: String, hits: List<ScopedHit>): List<IdeaCard> {
        // SÓ conteúdo (citadas): be/th instruem a estrutura, nunca são matéria
        val content = contentHits(hits)
        if (content.isEmpty()) return emptyList()
        val t = { i: Int -> content.getOrNull(i) ?: content[0] }
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
        // SÓ conteúdo (citadas): be/th instruem a estrutura, nunca são matéria
        val content = contentHits(hits)
        if (content.isEmpty()) {
            return "Nenhum trecho da matéria encontrado. Baixe as publicações-base e a matéria citada no esboço."
        }
        return content.take(3).mapIndexed { i, h -> "${i + 1}. [${h.source}] ${h.passage.text.take(160)}" }
            .joinToString("\n\n")
    }
}

/** Trechos de conteúdo (citadas), sem o guia be/th. Puro/testável. */
fun contentHits(hits: List<ScopedHit>): List<ScopedHit> = partitionGuideHits(hits).second

/**
 * Termos de busca: título + corpo + extras, sem stopwords, sem repetidos,
 * título primeiro. Garante que subtópicos do corpo cheguem à busca em vez
 * de morrer no corte de palavras. Puro/testável.
 */
fun queryTerms(title: String, body: String, extra: String, maxWords: Int): List<String> {
    fun words(s: String) = normalizeText(s).split(" ")
        .filter { it.length >= 3 && it !in STOPWORDS_PT }
    val out = LinkedHashSet<String>()
    for (w in words(title) + words(body) + words(extra)) {
        if (out.size >= maxWords) break
        out += w
    }
    return out.toList()
}

/**
 * Bônus de ranking por campo: título +2 (total ×3), corpo +1 (total ×2),
 * extras +0 (só o ponto base). Chaves já normalizadas, prontas para o
 * parâmetro boost do askScoped. Puro/testável.
 */
fun fieldBoost(title: String, body: String, extra: String): Map<String, Int> {
    fun words(s: String) = normalizeText(s).split(" ")
        .filter { it.length >= 3 && it !in STOPWORDS_PT }
    val out = mutableMapOf<String, Int>()
    for (w in words(extra)) out.putIfAbsent(w, 0)
    for (w in words(body)) out[w] = maxOf(out[w] ?: 0, 1)
    for (w in words(title)) out[w] = maxOf(out[w] ?: 0, 2)
    return out.filterValues { it > 0 }
}

/**
 * Reordena trechos por overlap com os termos da seção (estável: empates
 * mantêm a ordem do askScoped). Puro/testável.
 */
fun rerankByOverlap(hits: List<ScopedHit>, refWords: Set<String>): List<ScopedHit> {
    if (refWords.isEmpty()) return hits
    return hits.sortedByDescending { h ->
        val pw = normalizeText(h.passage.text).split(" ").toSet()
        refWords.count { it in pw }
    }
}

/**
 * Títulos das subseções filhas (nível maior que o da seção), até o próximo
 * título de mesmo nível ou menor. Puro/testável.
 */
fun childTitles(
    sections: List<com.bettertalker.app.data.util.OutlineSection>,
    idx: Int
): List<String> {
    if (idx !in sections.indices) return emptyList()
    val parentLevel = sections[idx].level
    val out = mutableListOf<String>()
    for (i in idx + 1 until sections.size) {
        val s = sections[i]
        if (s.level <= parentLevel) break
        out += s.title
    }
    return out
}

/**
 * Filtra trechos pelo capítulo/lição/estudo da seção indexada
 * ("Capítulo 5", "Lição 3", "Estudo 27"). Puro/testável.
 */
fun filterByChapter(
    passages: List<PassageEntity>,
    kind: String,
    number: Int
): List<PassageEntity> {
    val needles: List<String> = when (kind) {
        "cap" -> listOf("capitulo $number", "cap $number")
        "licao" -> listOf("licao $number")
        "estudo" -> listOf("estudo $number")
        else -> return passages.filter { p ->
            Regex("""\b$number\b""").containsMatchIn(normalizeText(p.section))
        }
    }
    return passages.filter { p ->
        val s = normalizeText(p.section)
        needles.any { s.contains(it) }
    }
}

/**
 * Trecho normalizado contém livro + capítulo + versículo como números
 * avulsos. Puro/testável.
 */
fun verseRefMatches(normText: String, bookNorm: String, chapter: Int, verse: Int): Boolean {
    if (!normText.contains(bookNorm)) return false
    val nums = Regex("""\b\d{1,3}\b""").findAll(normText)
        .mapNotNull { it.value.toIntOrNull() }.toSet()
    return nums.contains(chapter) && nums.contains(verse)
}

/** Frase real com sua fonte, para rascunhos extrativos. */
data class QuotedSentence(val text: String, val source: String)

/**
 * Seleciona frases dos trechos de conteúdo para o rascunho: filtra curtas,
 * deduplica por similaridade, cada uma com a fonte. Sem texto be/th.
 * Puro/testável.
 */
fun composeDraft(
    hits: List<ScopedHit>,
    maxSentences: Int = 5,
    minLength: Int = 40
): List<QuotedSentence> {
    val out = mutableListOf<QuotedSentence>()
    for (h in hits) {
        for (s in splitRawSentences(h.passage.text)) {
            val t = s.trim()
            if (t.length < minLength) continue
            if (out.any { PastedOutlineAnalyzer.similarity(it.text, t) >= 0.55 }) continue
            out += QuotedSentence(t, h.source)
            if (out.size >= maxSentences) return out
        }
    }
    return out
}

/**
 * Separa trechos-guia (be/th: estrutura) dos de conteúdo (citadas).
 * Puro/testável (nível superior para não exigir banco).
 */
fun partitionGuideHits(hits: List<ScopedHit>): Pair<List<ScopedHit>, List<ScopedHit>> {
    val guideTitles = BASE_PUBS.map { it.title }.toSet()
    val (guide, content) = hits.partition { h ->
        guideTitles.any { h.source.contains(it) }
    }
    return guide to content
}

/** Busca fixa da orientação de cada tipo de exemplo. Puro/testável. */
fun exampleGuideQuery(kind: CopilotRepository.ExampleKind): String = when (kind) {
    CopilotRepository.ExampleKind.INTRO -> "introdução prender atenção início"
    CopilotRepository.ExampleKind.ILLUSTRATION -> "ilustração exemplo cena"
    CopilotRepository.ExampleKind.CONCLUSION -> "conclusão aplicação terminar"
    CopilotRepository.ExampleKind.QUESTION -> "pergunta assistência resposta"
}

/**
 * Tipo de exemplo pedido pelas palavras (normalizadas); sem pista, vale a posição.
 * Puro/testável.
 */
fun exampleKindFor(
    isFirst: Boolean,
    isLast: Boolean,
    normalizedWords: String
): CopilotRepository.ExampleKind {
    val t = " $normalizedWords "
    fun has(vararg ws: String) = ws.any { w -> t.contains(w) }
    // pergunta antes: "inicial" contém "inici"
    if (has("pergunta")) return CopilotRepository.ExampleKind.QUESTION
    if (has("introduz", "introduc", "abrir", "comec", "comecar", "inici", "abertura")) {
        return CopilotRepository.ExampleKind.INTRO
    }
    if (has("conclu", "conclus", "termin", "fech", "encerr", "final")) {
        return CopilotRepository.ExampleKind.CONCLUSION
    }
    if (has("ilustr", "exemplo", "cena", "historia")) {
        return CopilotRepository.ExampleKind.ILLUSTRATION
    }
    return when {
        isFirst -> CopilotRepository.ExampleKind.INTRO
        isLast -> CopilotRepository.ExampleKind.CONCLUSION
        else -> CopilotRepository.ExampleKind.ILLUSTRATION
    }
}
