package com.bettertalker.app.data.export

import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.domain.speech.BodyConversion
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.OutlineConversion
import com.bettertalker.app.domain.speech.OutlineConverter
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import java.util.UUID

/**
 * T2 (Fase 2) — estrutura canônica emitida pelo modelo para "inserir tudo
 * na nota". Formato que o prompt pede:
 * ```
 * # INTRODUÇÃO
 * [texto]
 *
 * # SEÇÃO 1: [título]
 * [texto]
 *
 * # CONCLUSÃO
 * [texto]
 * ```
 * Tolerante: `#`/`##`, caixa alta/baixa, com/sem acento, com/sem número.
 * Puro/testável: sem Room, sem Android.
 */

enum class StructureRole { INTRO, BODY, CONCLUSION }

data class StructureSection(
    val role: StructureRole,
    val title: String,
    val contentHtml: String,
)

/** Minutos default por papel — mesmos de SectionsController/OutlineConverter. */
private const val MINUTES_INTRO = 1
private const val MINUTES_BODY = 5
private const val MINUTES_CONCLUSION = 1

private val HEADER_RE = Regex("""^#{1,3}\s+(.+?)\s*$""")
private val BOLD_RE = Regex("""\*\*(.+?)\*\*""")
private val ITALIC_RE = Regex("""\*(.+?)\*""")

/** Cabeçalho → papel (normalizado; BODY é o default). */
internal fun structureRoleOf(header: String): StructureRole {
    val n = " ${normalizeText(header)} "
    return when {
        Regex("\\b(introducao|abertura)\\b").containsMatchIn(n) -> StructureRole.INTRO
        Regex("\\b(conclusao|encerramento|fechamento)\\b").containsMatchIn(n) -> StructureRole.CONCLUSION
        else -> StructureRole.BODY
    }
}

/** Título: após ":" quando houver ("SEÇÃO 1: Esperança"); senão, o cabeçalho. */
internal fun structureTitleOf(header: String): String {
    val afterColon = header.substringAfter(":", "").trim()
    if (afterColon.isNotEmpty()) return afterColon.take(120)
    return header.trim().take(120)
}

/** Markdown simples → HTML simples (parágrafos + strong/em, com escape). */
fun markdownToSimpleHtml(text: String): String {
    fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    fun inline(s: String): String {
        var r = esc(s)
        r = BOLD_RE.replace(r) { "<strong>${it.groupValues[1]}</strong>" }
        r = ITALIC_RE.replace(r) { "<em>${it.groupValues[1]}</em>" }
        return r
    }
    return text.split(Regex("\\n\\s*\\n"))
        .map { it.replace("\n", " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("") { "<p>${inline(it)}</p>" }
}

/** Linha limpa para sub-ponto (sem marcadores markdown). */
internal fun plainLine(line: String): String {
    var r = line.trim()
    r = BOLD_RE.replace(r) { it.groupValues[1] }
    r = ITALIC_RE.replace(r) { it.groupValues[1] }
    return r.replace(Regex("\\s+"), " ").trim()
}

/**
 * Extrai as seções do markdown canônico. Seções vazias (sem título nem
 * conteúdo) são descartadas. Vazio = nada aproveitável.
 */
fun parseStructureMarkdown(text: String): List<StructureSection> {
    data class Acc(val header: String, val body: MutableList<String>)
    val accs = mutableListOf<Acc>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val h = HEADER_RE.matchEntire(line)?.groupValues?.get(1)?.trim()
        if (h != null && h.isNotEmpty()) {
            accs += Acc(h, mutableListOf())
        } else if (accs.isNotEmpty()) {
            accs.last().body += line
        }
    }
    return accs.mapNotNull { (header, body) ->
        val content = body.joinToString("\n").trim()
        val title = structureTitleOf(header)
        if (title.isBlank() && content.isBlank()) return@mapNotNull null
        StructureSection(
            role = structureRoleOf(header),
            title = title.ifBlank { "Seção" },
            contentHtml = markdownToSimpleHtml(content),
        )
    }
}

/**
 * Estrutura parseada → [OutlineConversion] pronta para
 * `OutlineImportService.persist` (mesmo contrato do `linkDraft`).
 * BODYs viram sub-pontos por linha (convenção do domínio); INTRO/CONCLUSION
 * levam o HTML. Orders contíguos. Puro/testável (ids/relógio injetáveis).
 */
fun toOutlineConversion(
    sections: List<StructureSection>,
    noteId: String,
    noteTitle: String,
    idProvider: () -> String = { UUID.randomUUID().toString() },
    now: () -> Long = { System.currentTimeMillis() },
): OutlineConversion {
    val ts = now()
    var order = 0
    var intro: SpeechSection? = null
    var conclusion: SpeechSection? = null
    val bodies = mutableListOf<BodyConversion>()
    for (s in sections) {
        when (s.role) {
            StructureRole.INTRO -> if (intro == null) {
                intro = SpeechSection(
                    id = idProvider(), noteId = noteId, order = order++,
                    role = SectionRole.INTRO, title = s.title,
                    minutes = MINUTES_INTRO, contentHtml = s.contentHtml,
                    bibleRefs = emptyList(), publicationRefs = emptyList(),
                    methodPrinciple = null, createdAt = ts, updatedAt = ts,
                )
            }
            StructureRole.CONCLUSION -> if (conclusion == null) {
                conclusion = SpeechSection(
                    id = idProvider(), noteId = noteId, order = order++,
                    role = SectionRole.CONCLUSION, title = s.title,
                    minutes = MINUTES_CONCLUSION, contentHtml = s.contentHtml,
                    bibleRefs = emptyList(), publicationRefs = emptyList(),
                    methodPrinciple = null, createdAt = ts, updatedAt = ts,
                )
            }
            StructureRole.BODY -> {
                val id = idProvider()
                val section = SpeechSection(
                    id = id, noteId = noteId, order = order++,
                    role = SectionRole.BODY, title = s.title,
                    minutes = MINUTES_BODY, contentHtml = "",
                    bibleRefs = emptyList(), publicationRefs = emptyList(),
                    methodPrinciple = null, createdAt = ts, updatedAt = ts,
                )
                val subs = s.contentHtml
                    .replace(Regex("</p>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .lines()
                    .map { plainLine(it) }
                    .filter { it.isNotBlank() }
                    .mapIndexed { i, line ->
                        SubPoint(
                            id = idProvider(), sectionId = id, order = i,
                            outlineText = line, bibleRefs = emptyList(),
                            publicationRefs = emptyList(), instruction = null,
                            developedHtml = "", createdAt = ts, updatedAt = ts,
                        )
                    }
                bodies += BodyConversion(section, subs)
            }
        }
    }
    // Orders contíguos na ordem final (intro, bodies, conclusion).
    var idx = 0
    val reIntro = intro?.copy(order = idx++)
    val reBodies = bodies.map { b -> b.copy(section = b.section.copy(order = idx++)) }
    val reConclusion = conclusion?.copy(order = idx)
    val totalMinutes = (listOfNotNull(reIntro) + reBodies.map { it.section } +
        listOfNotNull(reConclusion)).sumOf { it.minutes }
    return OutlineConversion(
        noteTitle = noteTitle,
        totalMinutes = totalMinutes,
        discourseType = DiscourseType.S34_DISCOURSE,
        intro = reIntro,
        bodies = reBodies,
        conclusion = reConclusion,
    )
}

/**
 * Anexa uma conversão nova à estrutura existente (modo "adicionar"):
 * existentes mantidos, novos após, orders renormalizados, intro/conclusion
 * existentes prevalecem. Puro/testável.
 */
fun mergeAppend(
    existingSections: List<SpeechSection>,
    existingSubPoints: Map<String, List<SubPoint>>,
    fresh: OutlineConversion,
): OutlineConversion {
    val ordered = existingSections.sortedBy { it.order }
    var idx = 0
    val intro = (ordered.firstOrNull { it.role == SectionRole.INTRO } ?: fresh.intro)
        ?.copy(order = idx++)
    val conclusionExisting = ordered.firstOrNull { it.role == SectionRole.CONCLUSION }
    val bodies = (
        ordered.filter { it.role == SectionRole.BODY }.map { s ->
            BodyConversion(s.copy(order = idx++), existingSubPoints[s.id].orEmpty())
        } + fresh.bodies.map { b -> b.copy(section = b.section.copy(order = idx++)) }
        )
    val conclusion = (conclusionExisting ?: fresh.conclusion)?.copy(order = idx)
    return fresh.copy(intro = intro, bodies = bodies, conclusion = conclusion)
}
