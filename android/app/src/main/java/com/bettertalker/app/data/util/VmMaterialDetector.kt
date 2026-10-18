package com.bettertalker.app.data.util

import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.canonicalPublicationSymbol

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
    /** UX "Colar e Pronto": cenário real (ex.: "DE CASA EM CASA"), se houver. */
    val scenario: String? = null,
    /** UX "Colar e Pronto": tipo da parte (ex.: "Cultivando o interesse"). */
    val partType: String? = null,
    /** S-38: referência da publicação da parte (ex.: "lmd lição 7 ponto 4"). */
    val reference: String? = null,
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

// #24-fix A1/A3: "Título: X (N min)" — formato real da apostila sem o
// cabeçalho de seção ou logo após ele.
private val TITULO_RE = Regex(
    """^Título:\s*(.+?)\s*\((\d{1,3})\s*min(?:utos?)?\)\s*$""",
    RegexOption.IGNORE_CASE,
)

// #24-fix A4: "Tópico N:" é corpo da parte — nunca vira número/título.
private val TOPICO_LINE_RE = Regex("""^Tópico\s+\d{1,2}\s*:""", RegexOption.IGNORE_CASE)

// #24-fix A2: cabeçalho colado ao título da parte ("…DEUS 1. Título (10 min)").
private val GLUED_PART_RE = Regex(
    """^[,\s.:–—-]*(\d{1,2})\.\s*(.+?)\s*\((\d{1,3})\s*min(?:utos?)?\)\s*$""",
    RegexOption.IGNORE_CASE,
)

// UX "Colar e Pronto": parte V&M SOLTA sem cabeçalho — "5. Título (4 min)"
// (rótulo/cenário/descrição podem seguir na mesma linha ou nas seguintes).
private val LOOSE_PART_RE = Regex(
    """^\s*(\d{1,2})[.)]?\s+(.+?)\s*\((\d{1,2})\s*min(?:utos?)?\)\s*(.*)$""",
    RegexOption.IGNORE_CASE,
)

/** Tipos/rótulos de parte do "Faça Seu Melhor no Ministério". */
private val VM_PART_TYPES = listOf(
    "Iniciando conversas",
    "Cultivando o interesse",
    "Fazendo discípulos",
    "Explicando suas crenças",
    "Discurso",
)

/** Cenários reais das apostilas (canônicos na saída; case-insensitive na busca). */
private val VM_SCENARIOS = listOf(
    "DE CASA EM CASA",
    "TESTEMUNHO INFORMAL",
    "TESTEMUNHO PÚBLICO",
)

/** Tipos que são demonstração quando há cenário (mesmo sem a palavra). */
private val VM_DEMO_PART_TYPES = listOf(
    "Iniciando conversas",
    "Cultivando o interesse",
    "Fazendo discípulos",
)

/**
 * S-38: referência de publicação no texto da parte — "(lmd lição 7 ponto 4)",
 * "lff 3:1-3". Puro/testável (reusado pelo DossierPromptBuilder).
 */
private val VM_REFERENCE_RE = Regex(
    """\(?\b((?:lmd|lff|th|be)\s+(?:li[çc][ãa]o\s*\d+(?:\s*,?\s*(?:ponto|par[áa]grafo)s?\s*\d+)?|\d+:\d+(?:-\d+)?))\)?""",
    RegexOption.IGNORE_CASE,
)

/** Cenário canônico do texto da parte ("DE CASA EM CASA"…), se houver. Puro. */
fun vmScenarioOf(text: String): String? {
    val haystack = text.lowercase()
    return VM_SCENARIOS.firstOrNull { haystack.contains(it.lowercase()) }
}

/** Referência da publicação no texto ("lmd lição 7 ponto 4"), se houver. Puro. */
fun vmReferenceOf(text: String): String? =
    VM_REFERENCE_RE.find(text)?.groupValues?.get(1)?.trim()

/** Rótulos explícitos de natureza no texto da parte. */
private val VM_LABELS = listOf("demonstração", "discurso", "consideração")

/** Termos que confirmam contexto V&M (apêndice ou papéis do diálogo). */
private val VM_CONTEXT_MARKERS = listOf(
    "de casa em casa",
    "testemunho informal",
    "testemunho público",
    "apêndice a",
    "faça seu melhor",
    "nossa vida cristã",
    "tesouros da palavra",
    "publicador:",
    "morador:",
)

/** Título e minutos de uma linha ("Discurso: X (5 min)" → "Discurso: X" + 5). */
private fun titleAndMinutes(line: String): Pair<String, Int?> {
    val m = MIN_RE.find(line) ?: return line.trim() to null
    val cleaned = line.removeRange(m.range)
        .trim()
        .trimEnd('(', ')', ' ', '-', '–', ':', '.', ',')
        .trim()
    return cleaned to m.groupValues[1].toIntOrNull()
}

/**
 * #24-fix A1 — material SEM cabeçalho de seção: "Título: X (N min)" na
 * primeira linha útil + ≥1 linha "Tópico N:" → parte 1 (TALK) de TESOUROS.
 * Puro/testável.
 */
private fun titleFirstMaterial(text: String): VmPart? {
    val lines = text.split("\n").map { it.trim() }
    val firstIdx = lines.indexOfFirst { it.isNotEmpty() }
    if (firstIdx < 0) return null
    // Formato com cabeçalho tem precedência: A1 só vale sem cabeçalho.
    if (lines.any { l -> SECTION_HEADERS.any { l.uppercase().startsWith(it.first) } }) return null
    val m = TITULO_RE.find(lines[firstIdx]) ?: return null
    if (lines.none { TOPICO_LINE_RE.containsMatchIn(it) }) return null
    val body = lines.drop(firstIdx + 1).joinToString("\n").trim()
    return VmPart(
        section = VmSection.TREASURES,
        partNumber = 1,
        title = m.groupValues[1].trim(),
        minutes = m.groupValues[2].toIntOrNull() ?: 0,
        nature = VmNature.TALK,
        body = body,
    )
}

/**
 * UX "Colar e Pronto" — parte V&M SOLTA (sem cabeçalho de seção):
 * "N. Título (M min) rótulo/cenário/descrição". Extrai o cenário e o tipo da
 * parte e classifica a natureza:
 * - rótulo "Demonstração" → DEMO; "Discurso"/"Consideração" → TALK;
 * - tipos Iniciando conversas/Cultivando o interesse/Fazendo discípulos com
 *   cenário → DEMO (mesmo sem a palavra "Demonstração");
 * - "Explicando suas crenças" → DEMO só com "Demonstração"; senão TALK.
 * A seção é inferida pelo número (1-3 Tesouros, 4-6 Ministério, 7-8 Nossa
 * Vida). Puro/testável.
 */
private fun loosePartMaterial(text: String): VmPart? {
    val lines = text.split("\n").map { it.trim() }
    val firstIdx = lines.indexOfFirst { it.isNotEmpty() }
    if (firstIdx < 0) return null
    // Formatos com cabeçalho/título-first têm precedência.
    if (lines.any { l -> SECTION_HEADERS.any { l.uppercase().startsWith(it.first) } }) return null
    val first = lines[firstIdx]
    val m = LOOSE_PART_RE.find(first) ?: return null
    val number = m.groupValues[1].toInt()
    val title = m.groupValues[2].trim()
    val minutes = m.groupValues[3].toIntOrNull() ?: 0
    val tail = m.groupValues[4].trim()
    val rest = listOf(tail, lines.drop(firstIdx + 1).joinToString("\n").trim())
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    val haystack = (first + "\n" + rest).lowercase()
    val partType = VM_PART_TYPES.firstOrNull { title.startsWith(it, ignoreCase = true) }
    val scenario = VM_SCENARIOS.firstOrNull { haystack.contains(it.lowercase()) }
    val hasSignal = partType != null || scenario != null ||
        VM_LABELS.any { haystack.contains(it) } ||
        VM_CONTEXT_MARKERS.any { haystack.contains(it) }
    if (!hasSignal) return null
    val section = when (number) {
        in 1..3 -> VmSection.TREASURES
        in 4..6 -> VmSection.MINISTRY
        in 7..8 -> VmSection.CHRISTIAN_LIFE
        else -> VmSection.MINISTRY
    }
    val lowerRest = rest.lowercase()
    val nature = when {
        lowerRest.contains("demonstração") -> VmNature.DEMO
        lowerRest.contains("consideração") -> VmNature.TALK
        lowerRest.contains("discurso") -> VmNature.TALK
        partType in VM_DEMO_PART_TYPES && scenario != null -> VmNature.DEMO
        partType == "Explicando suas crenças" -> VmNature.TALK
        else -> vmNatureOf(section, number, title) ?: VmNature.DEMO
    }
    return VmPart(
        section = section,
        partNumber = number,
        title = title,
        minutes = minutes,
        nature = nature,
        body = rest,
        scenario = scenario,
        partType = partType,
        reference = vmReferenceOf(rest.ifBlank { first }),
    )
}

/** Detecta material V&M; vazio quando não parece V&M. */
fun detectVmMaterial(text: String): List<VmPart> {
    // #24-fix A1: o formato real ("Título:" + "Tópico N:") não tem cabeçalho.
    titleFirstMaterial(text)?.let { return listOf(it) }
    // UX "Colar e Pronto": parte solta ("5. Título (4 min)" + cenário/papéis).
    loosePartMaterial(text)?.let { return listOf(it) }
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
                    scenario = vmScenarioOf(body.joinToString("\n")),
                    partType = VM_PART_TYPES.firstOrNull {
                        title.trim().startsWith(it, ignoreCase = true)
                    },
                    reference = vmReferenceOf(body.joinToString("\n")),
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
            } else {
                // #24-fix A2: cabeçalho colado ao título da parte
                // ("TESOUROS DA PALAVRA DE DEUS 1. Título (10 min)").
                val glued = GLUED_PART_RE.find(line.substring(header.first.length))
                if (glued != null) {
                    started = true
                    number = glued.groupValues[1].toInt()
                    title = glued.groupValues[2].trim()
                    minutes = glued.groupValues[3].toIntOrNull()
                }
            }
            continue
        }
        if (section == null) continue
        if (!started) {
            // Aguardando o início de uma parte ("1. Título" ou "1.").
            val part = PART_LINE_RE.find(line)
            if (part != null) {
                started = true
                number = part.groupValues[1].toInt()
                val (t, m) = titleAndMinutes(part.groupValues[2])
                title = t
                minutes = m
                continue
            }
            // #24-fix A3: cabeçalho + "Título: X (N min)" sem número —
            // trata como parte 1 (a natureza decide: TESOUROS 1 = TALK).
            val titulo = TITULO_RE.find(line)
            if (titulo != null) {
                started = true
                number = 1
                title = titulo.groupValues[1].trim()
                minutes = titulo.groupValues[2].toIntOrNull()
                continue
            }
            continue
        }
        // Coletando: título pendente / linha de tempo / corpo.
        if (title.isEmpty()) {
            if (line.isEmpty()) continue
            // #24-fix A4: "Tópico N:" é corpo da parte — nunca título/número.
            if (TOPICO_LINE_RE.containsMatchIn(line)) {
                body += raw
                continue
            }
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

/**
 * #24-fix C — refs estruturadas do corpo da parte TALK: Bíblia (canônica
 * "Je 45:3") + publicações (symbol/página/§). Puro/testável.
 */
fun extractRefsFromBody(body: String): Pair<List<String>, List<PublicationRef>> {
    val bible = RefDetector.detectBible(body)
        .mapNotNull { b ->
            normalizeBibleBookName(b.label)?.let { "$it ${b.chapter}:${b.verse}" }
        }
        .distinct()
    val pubs = RefDetector.detect(body)
        .map { ref ->
            PublicationRef(
                symbol = canonicalPublicationSymbol(ref.pubKey, ref.editionKey),
                page = ref.page,
                paragraph = ref.paragraph,
                paragraphEnd = ref.paragraphEnd,
                article = ref.article,
                chapter = ref.chapter,
            )
        }
        .distinct()
    return bible to pubs
}

/** Seção BODY que materializa a parte TALK colada (fonte do gerador). */
fun vmTalkSection(
    talk: VmPart,
    noteId: String,
    order: Int,
    id: String,
    now: Long,
): SpeechSection {
    // #24-fix C: as refs do corpo viram refs estruturadas da seção — o
    // dossiê do chat sobre a seção TALK funciona antes do apply.
    val (bibleRefs, publicationRefs) = extractRefsFromBody(talk.body)
    return SpeechSection(
        id = id,
        noteId = noteId,
        order = order,
        role = SectionRole.BODY,
        title = talk.title,
        minutes = talk.minutes,
        contentHtml = htmlFromText(talk.body),
        bibleRefs = bibleRefs,
        publicationRefs = publicationRefs,
        methodPrinciple = null,
        createdAt = now,
        updatedAt = now,
    )
}
