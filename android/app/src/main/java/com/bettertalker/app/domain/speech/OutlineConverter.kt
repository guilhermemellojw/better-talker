package com.bettertalker.app.domain.speech

import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.ParsedOutline
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.domain.planning.PublicationRef
import java.util.UUID

/**
 * Resultado da conversão de um esboço em estrutura de seções + sub-pontos.
 *
 * Intro e Conclusion são sintetizadas vazias (o usuário preenche ou o Copilot
 * gera). Cada [OutlineSection] vira 1 BODY com seus sub-pontos extraídos do body.
 *
 * @param noteTitle título do discurso (do esboço)
 * @param totalMinutes tempo total (do esboço ou soma das seções)
 * @param discourseType tipo que originou a conversão (lido pelo persist).
 * @param intro seção INTRO sintetizada — SEMPRE null: a introdução NÃO é
 *   criada automaticamente a partir de texto antes do primeiro tópico.
 * @param bodies seções BODY com sub-pontos (orders 0..N contíguos)
 * @param conclusion seção CONCLUSION sintetizada — SEMPRE null (não inferida).
 * @param speakerNotes orientações gerais do orador (NOTA inicial + fechamento).
 */
data class OutlineConversion(
    val noteTitle: String,
    val totalMinutes: Int,
    val discourseType: DiscourseType,
    val intro: SpeechSection?,
    val bodies: List<BodyConversion>,
    val conclusion: SpeechSection?,
    val speakerNotes: String = "",
)

/**
 * Uma seção BODY convertida com seus sub-pontos.
 *
 * @param section seção BODY (contentHtml vazio; refs derivadas dos sub-pontos)
 * @param subPoints sub-pontos extraídos do body (order 0-based contíguo)
 */
data class BodyConversion(
    val section: SpeechSection,
    val subPoints: List<SubPoint>,
)

/**
 * Converte um [ParsedOutline] em [OutlineConversion] (em memória, sem persistir).
 *
 * Invariantes garantidas:
 * - `intro.role == INTRO` e `conclusion.role == CONCLUSION`
 * - Cada `body.section.role == BODY` e `contentHtml` vazio
 * - Ordem final: intro (0), bodies (1..N), conclusion (N+1) — contígua
 * - Sub-pontos 0-based contíguos dentro de cada body
 * - Ids gerados via [idProvider]; timestamps via [now]
 *
 * One sub-point per body line; linhas que são só refs anexam ao sub-ponto
 * anterior (nunca viram sub-ponto solto, exceto se forem a primeira linha).
 *
 * @param idProvider gerador de ids (UUID por default; injetável para teste)
 * @param now relógio (System.currentTimeMillis por default; injetável)
 */
class OutlineConverter(
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Converte [parsed] em [OutlineConversion].
     *
     * S-34 (default): INTRO + N BODY + CONCLUSION. Tipos curtos
     * (TREASURES_TALK/MINISTRY_PART/AVULSO): exatamente 1 OutlineSection vira
     * o único BODY (order 0); intro/conclusion nulos.
     *
     * DÉBITO (3.2.4b):
     * - Detecção automática de tipo vem na 3.2.4d (aqui o tipo é parâmetro).
     * - Parser da apostila Vida e Ministério vem na 3.2.4c (aqui recebe
     *   ParsedOutline já pronto).
     */
    fun convert(
        parsed: ParsedOutline,
        noteId: String,
        discourseType: DiscourseType = DiscourseType.S34_DISCOURSE,
    ): OutlineConversion {
        val ts = now()
        val total = parsed.totalMinutes ?: parsed.sections.sumOf { it.minutes ?: 0 }

        if (discourseType != DiscourseType.S34_DISCOURSE) {
            require(parsed.sections.size == 1) {
                "$discourseType exige exatamente 1 section, recebeu ${parsed.sections.size}"
            }
            val outline = parsed.sections.first()
            val section = buildBodySection(outline, noteId, order = 0, ts)
            return OutlineConversion(
                noteTitle = parsed.title,
                totalMinutes = parsed.totalMinutes ?: 0,
                discourseType = discourseType,
                intro = null,
                bodies = listOf(BodyConversion(section, extractSubPoints(outline.body, section.id, ts))),
                conclusion = null,
            )
        }

        val bodies = parsed.sections.mapIndexed { i, outline ->
            // Ordem por posição (contígua), não por outline.order — invariante.
            val section = buildBodySection(outline, noteId, order = i, ts)
            BodyConversion(section, extractSubPoints(outline.body, section.id, ts))
        }

        // F3.x: NÃO sintetiza INTRO/CONCLUSION. Texto antes do primeiro tópico
        // é orientação do orador (`speakerNotes`), nunca introdução. A intro e
        // a conclusão só existem quando o usuário as construir.
        return OutlineConversion(
            noteTitle = parsed.title,
            totalMinutes = total,
            discourseType = DiscourseType.S34_DISCOURSE,
            intro = null,
            bodies = bodies,
            conclusion = null,
            speakerNotes = parsed.preamble.trim(),
        )
    }

    /**
     * Constrói 1 seção BODY a partir de 1 [OutlineSection].
     * Reusado pelo caminho S-34 e pelos tipos curtos.
     */
    private fun buildBodySection(outline: OutlineSection, noteId: String, order: Int, ts: Long): SpeechSection {
        return SpeechSection(
            id = idProvider(),
            noteId = noteId,
            order = order,
            role = SectionRole.BODY,
            title = outline.title,
            // 3.2.3c: seção sem tempo ganha fallback (5 min) — esboços
            // colados sem "(N min)" não ficam sem seções. Zero explícito
            // NÃO é "consertado" (o validator rejeita NON_POSITIVE_MINUTES).
            minutes = outline.minutes ?: FALLBACK_MINUTES,
            contentHtml = "",
            bibleRefs = emptyList(),
            publicationRefs = emptyList(),
            methodPrinciple = null,
            createdAt = ts,
            updatedAt = ts,
        )
    }

    // ---------- Sub-pontos ----------

    /**
     * Agrupa linhas do body em sub-pontos: linha de texto inicia grupo;
     * linha só-ref anexa ao grupo anterior (ou inicia o primeiro, se não houver).
     */
    private fun extractSubPoints(body: String, sectionId: String, ts: Long): List<SubPoint> {
        val groups = mutableListOf<MutableList<String>>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (isOnlyRef(line) && groups.isNotEmpty()) {
                groups.last().add(line)
            } else {
                groups.add(mutableListOf(line))
            }
        }
        val out = mutableListOf<SubPoint>()
        for (group in groups) {
            val sp = buildSubPoint(group, sectionId, out.size, ts) ?: continue
            out.add(sp)
        }
        return out
    }

    /**
     * Extrai sub-pontos do body. Refs bíblicas são normalizadas para a
     * abreviação TNM 2015 (ex.: "Gênesis 3:6" → "Gên 3:6") via
     * [normalizeBibleBookName]. O `outlineText` preserva o texto original
     * (não é normalizado); apenas parênteses que contêm ref detectada são
     * removidos dele.
     */
    private fun buildSubPoint(group: List<String>, sectionId: String, order: Int, ts: Long): SubPoint? {
        val primary = stripMarker(group.first())
        val instruction = group.mapNotNull { extractInstruction(it) }
            .joinToString(" ")
            .ifBlank { null }
        val bibleRefs = group.flatMap { line ->
            RefDetector.detectBible(line).map {
                val abbrev = normalizeBibleBookName(it.label) ?: it.label
                "$abbrev ${it.chapter}:${it.verse}"
            }
        }.distinct()
        val publicationRefs = group.flatMap { line ->
            RefDetector.detect(line).map { ref ->
                // Parágrafo/página a partir do trecho IMEDIATAMENTE após a ref
                // na linha (o raw sozinho não carrega "§ N" nem a página).
                val idx = line.indexOf(ref.raw)
                val tail = if (idx >= 0) line.substring(idx + ref.raw.length) else ""
                val isOldFormat = ref.editionKey.split("|").size >= 4
                val page = if (isOldFormat) extractPageFromTail(tail) else null
                val paragraph = if (isOldFormat) {
                    null
                } else {
                    RefDetector.chapterOf(tail)
                        ?.takeIf { it.kind == "paragrafo" }?.number
                }
                PublicationRef(
                    symbol = canonicalPublicationSymbol(ref.pubKey, ref.editionKey),
                    page = ref.page ?: page,
                    paragraph = ref.paragraph ?: paragraph,
                    article = ref.article,
                    chapter = ref.chapter,
                )
            }
        }.distinct()
        val outlineText = cleanOutlineText(primary)
        if (outlineText.isBlank() && bibleRefs.isEmpty() && publicationRefs.isEmpty() && instruction == null) {
            return null
        }
        return SubPoint(
            id = idProvider(),
            sectionId = sectionId,
            order = order,
            outlineText = outlineText,
            bibleRefs = bibleRefs,
            publicationRefs = publicationRefs,
            instruction = instruction,
            developedHtml = "",
            createdAt = ts,
            updatedAt = ts,
        )
    }

    // ---------- Helpers ----------

    /** Remove marcador de lista do início: "1. ", "a) ", "- ", "* ", "• ", "– ". */
    private fun stripMarker(line: String): String {
        val m = MARKER_RE.find(line) ?: return line
        return line.substring(m.range.last + 1).trimStart()
    }

    /** Linha composta apenas de grupos (...) e/ou [...] (refs/instruções soltos). */
    private fun isOnlyRef(line: String): Boolean = ONLY_REF_RE.matches(line.trim())

    /** Primeiro conteúdo entre colchetes; múltiplos são concatenados com espaço. */
    private fun extractInstruction(line: String): String? {
        val found = BRACKET_RE.findAll(line).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return found.joinToString(" ").ifBlank { null }
    }

    /**
     * Texto limpo do sub-ponto: remove instruções `[...]` e grupos `(...)`
     * que contenham refs detectáveis (bíblia ou publicação). Parênteses
     * sem ref permanecem (texto legítimo).
     */
    private fun cleanOutlineText(line: String): String {
        var s = BRACKET_RE.replace(line, " ")
        s = PAREN_GROUP_RE.replace(s) { m ->
            val inner = m.groupValues[1]
            val hasRef = RefDetector.detect(inner).isNotEmpty() ||
                RefDetector.detectBible(inner).isNotEmpty()
            if (hasRef) " " else m.value
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Extrai página do tail imediatamente após uma ref de formato antigo.
     * Ex: tail " 3) e ..." de "(w94 1/8 3)" → 3; " 3-4)" → 3 (primeiro da faixa).
     * Retorna null se não houver número.
     */
    private fun extractPageFromTail(tail: String): Int? =
        Regex("""^\s*(\d{1,4})""").find(tail)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        val MARKER_RE = Regex("""^\s*(?:\(?\d{1,2}\)?[.)]|[a-z][.)]|[-*•–])\s+""", RegexOption.IGNORE_CASE)
        val ONLY_REF_RE = Regex("""^(?:\s*[(\[][^)\]]*[)\]]\s*)+$""")
        val BRACKET_RE = Regex("""\[([^\]]*)\]""")
        val PAREN_GROUP_RE = Regex("""\(([^)]*)\)""")

        /** Duração assumida para seções BODY sem tempo no esboço (3.2.3c). */
        const val FALLBACK_MINUTES = 5
    }
}

/**
 * Canonicaliza `pubKey + editionKey` (DetectedRef) para o formato do índice EPUB.
 *
 * Regras (3.2.3a-fix2):
 * - `w` + `w|YYYY|M` → `"wYY.MM"` (Sentinela estudo)
 * - `wp` + `wp|YYYY|N` → `"wpYY.NN"` (Sentinela pública)
 * - `g` + `g|YYYY|M` → `"g M/YY"` (Despertai, com espaço)
 * - `gn` + `gn|YYYY|N` → `"gn N/YY"` (Despertai pública, edição)
 * - Livros (`editionKey = "book|$sym"`) ou pubKey desconhecido → só pubKey
 * - editionKey vazio/malformado → só pubKey
 *
 * CRÍTICO: preserva a distinção `w` (estudo) vs `wp` (pública) — mesmo
 * ano/mês produz símbolos diferentes.
 *
 * DÉBITO TÉCNICO (3.2.3a-fix2):
 * - Formatos não reconhecidos pelo RefDetector ("Sentinela" pura, "estudo 5",
 *   "nwtsty", "Ache") não são canonicalizados.
 * - `pág. N` não é extraído como número (só rótulo).
 * - `§§M-N` retorna só o primeiro parágrafo.
 * - Para `wp`, o terceiro campo do editionKey é o NÚMERO da edição (não mês);
 *   a formatação "wpYY.NN" segue a decisão do usuário — o cruzamento com
 *   nomes de arquivo (que usam mês) exige [RefDetector.wpIssueMonths] depois.
 */
internal fun canonicalPublicationSymbol(pubKey: String, editionKey: String): String {
    if (editionKey.isBlank() || editionKey.startsWith("book|")) return pubKey
    val parts = editionKey.split("|")
    if (parts.size < 3) return pubKey
    val key = parts[0]
    val year = parts[1]
    val num = parts[2]
    if (key != pubKey) return pubKey
    if (year.isBlank() || num.isBlank()) return pubKey
    if (year.any { !it.isDigit() } || num.any { !it.isDigit() }) return pubKey
    val yy = if (year.length == 4) year.takeLast(2) else year
    // Formato quinzenal antigo (4 partes: pub|YYYY|M|D) → "w94 1/8" / "g93 8/1".
    if (parts.size >= 4) {
        val day = parts[3]
        if (day.isBlank() || day.any { !it.isDigit() }) return pubKey
        return "$key$yy $day/$num"
    }
    return when (key) {
        "w", "wp" -> "$key$yy.${num.padStart(2, '0')}"
        "g", "gn" -> "$key $num/$yy"
        else -> pubKey
    }
}
