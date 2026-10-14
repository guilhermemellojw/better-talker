package com.bettertalker.app.data.util

import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection

/**
 * #8b — detecção de material da reunião Vida e Ministério (V&M).
 *
 * O texto colado é reconhecido por cabeçalhos de seção (TESOUROS / FAÇA SEU
 * MELHOR / NOSSA VIDA) e cada parte ganha uma natureza:
 * - TALK gera discurso completo (TESOUROS 1, FAÇA 6-discurso, NOSSA VIDA 7);
 * - DEMO é reconhecido mas avisa "em breve";
 * - GEMS / BIBLE_READING / CONGREGATION_STUDY são rejeitados com mensagem.
 *
 * Puro/testável — o CopilotViewModel só consome o plano.
 */

enum class VmSection { TREASURES, MINISTRY, CHRISTIAN_LIFE }

enum class VmNature { TALK, DEMO, GEMS, BIBLE_READING, CONGREGATION_STUDY }

data class VmPart(
    val section: VmSection,
    val partNumber: Int,
    val title: String,
    val minutes: Int,
    val nature: VmNature,
    val body: String,
)

fun VmSection.label(): String = when (this) {
    VmSection.TREASURES -> "TESOUROS DA PALAVRA DE DEUS"
    VmSection.MINISTRY -> "FAÇA SEU MELHOR NO MINISTÉRIO"
    VmSection.CHRISTIAN_LIFE -> "NOSSA VIDA CRISTÃ"
}

fun VmNature.label(): String = when (this) {
    VmNature.TALK -> "discurso"
    VmNature.DEMO -> "demonstração"
    VmNature.GEMS -> "Joias espirituais"
    VmNature.BIBLE_READING -> "Leitura da Bíblia"
    VmNature.CONGREGATION_STUDY -> "Estudo bíblico de congregação"
}

/** Natureza pela seção+número; null = fora da tabela (parte ignorada). */
fun vmNatureOf(section: VmSection, partNumber: Int, title: String): VmNature? =
    when (section) {
        VmSection.TREASURES -> when (partNumber) {
            1 -> VmNature.TALK
            2 -> VmNature.GEMS
            3 -> VmNature.BIBLE_READING
            else -> null
        }
        VmSection.MINISTRY -> when (partNumber) {
            4, 5 -> VmNature.DEMO
            6 -> if (title.contains("discurso", ignoreCase = true)) {
                VmNature.TALK
            } else {
                VmNature.DEMO
            }
            else -> null
        }
        VmSection.CHRISTIAN_LIFE -> when (partNumber) {
            7 -> VmNature.TALK
            8 -> VmNature.CONGREGATION_STUDY
            else -> null
        }
    }

private val SECTION_HEADERS = listOf(
    "TESOUROS DA PALAVRA DE DEUS" to VmSection.TREASURES,
    "FAÇA SEU MELHOR NO MINISTÉRIO" to VmSection.MINISTRY,
    "NOSSA VIDA CRISTÃ" to VmSection.CHRISTIAN_LIFE,
)
private val PART_LINE_RE = Regex("""^(\d{1,2})\.\s*(.*)$""")
private val INLINE_POINT_RE = Regex("""ponto\s+(\d{1,2})""", RegexOption.IGNORE_CASE)
private val MIN_RE = Regex("""(\d{1,3})\s*(?:min|minutos?)\b""", RegexOption.IGNORE_CASE)
private val TIME_ONLY_RE = Regex("""^\(?\s*\d{1,3}\s*(?:min|minutos?)\s*\)?\.?$""", RegexOption.IGNORE_CASE)

/** Título e minutos de uma linha ("Discurso: X (5 min)" → "Discurso: X" + 5). */
private fun titleAndMinutes(line: String): Pair<String, Int?> {
    val m = MIN_RE.find(line) ?: return line.trim() to null
    val cleaned = line.removeRange(m.range)
        .trim()
        .trimEnd('(', ')', ' ', '-', '–', ':', '.', ',')
        .trim()
    return cleaned to m.groupValues[1].toIntOrNull()
}

/** Detecta material V&M; vazio quando não parece V&M. */
fun detectVmMaterial(text: String): List<VmPart> {
    val out = mutableListOf<VmPart>()
    var section: VmSection? = null
    var started = false
    var number = 0
    var title = ""
    var minutes: Int? = null
    val body = mutableListOf<String>()

    fun flush() {
        if (started) {
            val sec = section
            val nat = sec?.let { vmNatureOf(it, number, title) }
            if (sec != null && nat != null) {
                out += VmPart(
                    section = sec,
                    partNumber = number,
                    title = title.trim(),
                    minutes = minutes ?: 0,
                    nature = nat,
                    body = body.joinToString("\n").trim(),
                )
            }
        }
        started = false
        number = 0
        title = ""
        minutes = null
        body.clear()
    }

    for (raw in text.split("\n")) {
        val line = raw.trim()
        val upper = line.uppercase()
        val header = SECTION_HEADERS.firstOrNull { upper.startsWith(it.first) }
        if (header != null) {
            flush()
            section = header.second
            // Formato 1: número e minutos no próprio cabeçalho.
            val inline = INLINE_POINT_RE.find(line)
            if (inline != null) {
                started = true
                number = inline.groupValues[1].toInt()
                minutes = MIN_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            }
            continue
        }
        if (section == null) continue
        if (!started) {
            // Aguardando o início de uma parte ("1. Título" ou "1.").
            val part = PART_LINE_RE.find(line) ?: continue
            started = true
            number = part.groupValues[1].toInt()
            val (t, m) = titleAndMinutes(part.groupValues[2])
            title = t
            minutes = m
            continue
        }
        // Coletando: título pendente / linha de tempo / corpo.
        if (title.isEmpty()) {
            if (line.isEmpty()) continue
            if (TIME_ONLY_RE.matches(line)) {
                minutes = minutes ?: MIN_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                continue
            }
            val (t, m) = titleAndMinutes(line)
            title = t
            minutes = minutes ?: m
            continue
        }
        if (minutes == null && TIME_ONLY_RE.matches(line)) {
            minutes = MIN_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            continue
        }
        body += raw
    }
    flush()
    return out
}

/** #8b: roteamento do paste de material V&M. */
sealed interface VmPastePlan {
    /** Discurso: gera a partir da primeira parte TALK; as demais são ignoradas. */
    data class Talk(val talk: VmPart, val ignored: List<VmPart>) : VmPastePlan

    /** Material V&M sem nenhuma parte TALK: rejeição limpa (sem draft). */
    data class Rejected(val parts: List<VmPart>) : VmPastePlan

    /** Não é V&M: fluxo genérico (S-34/candidatos) intacto. */
    object Generic : VmPastePlan
}

fun vmPastePlan(text: String): VmPastePlan {
    val parts = detectVmMaterial(text)
    if (parts.isEmpty()) return VmPastePlan.Generic
    val talk = parts.firstOrNull { it.nature == VmNature.TALK }
        ?: return VmPastePlan.Rejected(parts)
    return VmPastePlan.Talk(talk, parts - talk)
}

/** Mensagem do chat quando há TALK (com o resto ignorado, se houver). */
fun vmTalkMessage(talk: VmPart, ignored: List<VmPart>): String = buildString {
    append("Reconheci material V&M.\n")
    append(
        "✓ Parte ${talk.partNumber} (${talk.section.label()}): ${talk.title} " +
            "(${talk.minutes} min) — vou gerar discurso completo."
    )
    if (ignored.isNotEmpty()) {
        append("\n\nIgnorei: ")
        append(ignored.joinToString("; ") { "parte ${it.partNumber} — ${it.nature.label()}" })
        append(".")
    }
}

/** Mensagem do chat quando nenhuma parte é discurso (rejeição limpa). */
fun vmRejectionMessage(parts: List<VmPart>): String = buildString {
    append("Reconheci material V&M, mas nenhuma das partes é um discurso:\n")
    parts.forEach { p ->
        val nat = if (p.nature == VmNature.DEMO) {
            "${p.nature.label()} (em breve)"
        } else {
            p.nature.label()
        }
        append("- ${p.section.label()} parte ${p.partNumber} — $nat\n")
    }
    append(
        "\nO app processa apenas discursos (TESOUROS 1, NOSSA VIDA 7, " +
            "FAÇA SEU MELHOR parte 6 quando é discurso)."
    )
}

/** HTML mínimo do rich editor (parágrafos + quebras de linha). */
fun htmlFromText(text: String): String {
    val escaped = text.trim()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return escaped.split(Regex("\n\\s*\n"))
        .filter { it.isNotBlank() }
        .joinToString("") { "<p>" + it.trim().replace("\n", "<br>") + "</p>" }
}

/** Minutos embutidos na natureza do material ("TALK:10min" → 10). */
fun materialMinutesOf(materialNature: String?): Int? =
    materialNature?.substringAfter(':', "")?.let {
        Regex("""(\d{1,3})""").find(it)?.groupValues?.get(1)?.toIntOrNull()
    }

/** Seção BODY que materializa a parte TALK colada (fonte do gerador). */
fun vmTalkSection(
    talk: VmPart,
    noteId: String,
    order: Int,
    id: String,
    now: Long,
): SpeechSection = SpeechSection(
    id = id,
    noteId = noteId,
    order = order,
    role = SectionRole.BODY,
    title = talk.title,
    minutes = talk.minutes,
    contentHtml = htmlFromText(talk.body),
    bibleRefs = emptyList(),
    publicationRefs = emptyList(),
    methodPrinciple = null,
    createdAt = now,
    updatedAt = now,
)
