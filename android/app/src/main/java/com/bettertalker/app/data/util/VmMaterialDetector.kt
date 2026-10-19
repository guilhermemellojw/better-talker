package com.bettertalker.app.data.util

import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.canonicalPublicationSymbol

/**
 * #8b — detecção de material da reunião Vida e Ministério (V&M).
 *
 * Fase A: parser ÚNICO. A mesma linha de parte (com ou sem cabeçalho de
 * seção) passa por [parsePartLine] e produz o mesmo título/cauda/corpo.
 * Antes o caminho com cabeçalho usava `titleAndMinutes` e engolia a cauda
 * (`DE CASA EM CASA...`) no título; o caminho solto usava outra regex.
 *
 * O detector também aceita múltiplas partes soltas, minutos na linha
 * seguinte/variantes (`4 min.`, `4 minutos`, sem parênteses) e texto
 * normalizado por [PasteTextNormalizer] (NBSP, bullets de PDF).
 *
 * Puro/testável — o CopilotViewModel só consome o plano.
 */

enum class VmSection { TREASURES, MINISTRY, CHRISTIAN_LIFE }

enum class VmNature { TALK, DEMO, CONSIDERATION, GEMS, BIBLE_READING, CONGREGATION_STUDY }

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
    VmNature.CONSIDERATION -> "consideração"
    VmNature.GEMS -> "Joias espirituais"
    VmNature.BIBLE_READING -> "Leitura da Bíblia"
    VmNature.CONGREGATION_STUDY -> "Estudo bíblico de congregação"
}

/**
 * Natureza da parte. Para MINISTRY, a classificação é por **título + rótulo**
 * (o que a apostila diz), não pela posição 4/5/6.
 *
 * Casos reais cobertos:
 * - "5. Explicar as suas crenças (5 min) Discurso" → TALK;
 * - "6. Explicar as suas crenças (5 min) Demonstração" → DEMO;
 * - "6. Discurso (5 min)" → TALK;
 * - "6. O que você diria? (6 min) Consideração" → CONSIDERATION;
 * - "4. Perseverança – O que Paulo fez (7 min) Discurso..." → TALK.
 *
 * `body` é necessário para ler o rótulo quando ele vem depois dos minutos.
 * O parâmetro tem default para preservar chamadas antigas. Puro/testável.
 */
fun vmNatureOf(
    section: VmSection,
    partNumber: Int,
    title: String,
    body: String = "",
): VmNature? = when (section) {
    VmSection.TREASURES -> when (partNumber) {
        1 -> VmNature.TALK
        2 -> VmNature.GEMS
        3 -> VmNature.BIBLE_READING
        else -> null
    }
    VmSection.MINISTRY -> {
        val t = normalizeText(title)
        val haystack = normalizeText("$title $body")
        when {
            // Tipos de demonstração (título é o tipo da parte).
            VM_DEMO_PART_TYPES.any { t.startsWith(it) } -> VmNature.DEMO

            // "Explicando/Explicar as suas crenças" pode ser demo OU discurso;
            // o rótulo manda. Sem rótulo, mantém o default histórico (DEMO).
            t.startsWith("explicando suas crencas") ||
            t.startsWith("explicando as suas crencas") ||
            t.startsWith("explicar as suas crencas") ||
            t.startsWith("explicar suas crencas") -> when {
                haystack.contains("consideracao") -> VmNature.CONSIDERATION
                haystack.contains("demonstracao") -> VmNature.DEMO
                haystack.contains("discurso") -> VmNature.TALK
                else -> VmNature.DEMO
            }

            t.startsWith("discurso") -> VmNature.TALK

            // "O que você diria?" é a consideração; o rótulo também confirma.
            t.startsWith("o que voce diria") ||
            haystack.contains("consideracao") -> VmNature.CONSIDERATION

            // Rótulos explícitos no título/corpo (ex.: Perseverança + Discurso).
            haystack.contains("demonstracao") -> VmNature.DEMO
            haystack.contains("discurso") -> VmNature.TALK

            else -> null
        }
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

/** "ponto 4" no próprio cabeçalho da seção. */
private val INLINE_POINT_RE = Regex("""ponto\s+(\d{1,2})""", RegexOption.IGNORE_CASE)

/** Minutos em qualquer posição (usado no título/cabeçalho legado). */
private val MIN_RE = Regex("""(\d{1,3})\s*(?:min|minutos?)\b""", RegexOption.IGNORE_CASE)

/** Linha composta só de "(4 min)" / "4 minutos". */
private val TIME_ONLY_RE = Regex(
    """^\(?\s*\d{1,3}\s*(?:min|minutos?)\.?\s*\)?\.?$""",
    RegexOption.IGNORE_CASE,
)

/** Ref bíblica "Livro C:V" — inclusive abreviações fora do mapa do RefDetector. */
private val BIBLE_REF_HINT_RE = Regex(
    """\b(?:[1-3]\s*)?[A-Za-zÀ-ÿ]{2,}\.?\s+\d{1,3}\s*:\s*\d{1,3}\b""",
)

// #24-fix A1/A3: "Título: X (N min)" — formato real da apostila sem o
// cabeçalho de seção ou logo após ele.
private val TITULO_RE = Regex(
    """^Título:\s*(.+?)\s*\((\d{1,3})\s*min(?:utos?)?\.?\)\s*$""",
    RegexOption.IGNORE_CASE,
)

// #24-fix A4: "Tópico N:" é corpo da parte — nunca vira número/título.
private val TOPICO_LINE_RE = Regex("""^Tópico\s+\d{1,2}\s*:""", RegexOption.IGNORE_CASE)

/**
 * Prefixo de parte: `5.`, `5)`, `5 -`, `5 .` ou `5 `.
 * O número é limitado a 2 dígitos para não sequestrar anos/medidas.
 */
private val PART_PREFIX_RE = Regex("""^\s*(\d{1,2})\s*[.)\-–—:]?\s+(.+)$""")

/** Minutos entre parênteses: "(4 min)", "(4 min.)", "(4 minutos)". */
private val PAREN_MIN_RE = Regex(
    """\(\s*(\d{1,3})\s*min(?:utos?)?\.?\s*\)""",
    RegexOption.IGNORE_CASE,
)

/** Minutos sem parênteses: "4 min", "4 min.". */
private val BARE_MIN_RE = Regex(
    """(?:^|\s)(\d{1,3})\s*min(?:utos?)?\.?(?=\s|$|[,;:)])""",
    RegexOption.IGNORE_CASE,
)

private val EMPTY_PARENS_RE = Regex("""\(\s*\)""")
private val MULTI_SPACE_RE = Regex("""\s{2,}""")

/** Tipos/rótulos de parte do "Faça Seu Melhor no Ministério". */
private val VM_PART_TYPES = listOf(
    "Iniciando conversas",
    "Cultivando o interesse",
    "Fazendo discípulos",
    "Fazer discípulos",
    "Faça discípulos",
    "Comece uma conversa",
    "Começar uma conversa",
    "Faça revisitas",
    "Explicando suas crenças",
    "Explicar as suas crenças",
    "Discurso",
    "O que você diria?",
    "Perseverança",
)

/** Cenários reais das apostilas (canônicos na saída; case-insensitive na busca). */
private val VM_SCENARIOS = listOf(
    "DE CASA EM CASA",
    "TESTEMUNHO INFORMAL",
    "TESTEMUNHO PÚBLICO",
)

/**
 * Prefixos (normalizados: sem acento/caixa) que identificam uma demonstração
 * pelo título da parte, mesmo sem cenário. Inclui as variações reais das
 * apostilas ("Comece uma conversa", "Faça revisitas", "Visita inicial").
 */
private val VM_DEMO_PART_TYPES = listOf(
    "iniciando conversas",
    "cultivando o interesse",
    "fazendo discipulos",
    "fazer discipulos",
    "faca discipulos",
    "comece uma conversa",
    "comecar uma conversa",
    "inicie uma conversa",
    "faca revisitas",
    "fazer revisitas",
    "visita",
    "revisita",
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

/** Rótulos explícitos de natureza no texto da parte (com e sem acento). */
private val VM_LABELS = listOf(
    "demonstração", "demonstracao",
    "discurso",
    "consideração", "consideracao",
)

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

/**
 * Naturezas/títulos típicos que confirmam V&M mesmo sem o cabeçalho — cobre
 * a apostila solta com Joias/Leitura/parte 7 (as demais já têm partType ou
 * cenário).
 */
private val VM_NATURE_MARKERS = listOf(
    "joias espirituais",
    "leitura da bíblia",
    "estudo bíblico de congregação",
    "faça discípulos",
)

/** Cabeçalho de seção no início da linha, se houver. */
private fun headerOf(line: String): Pair<String, VmSection>? {
    val upper = line.trim().uppercase()
    return SECTION_HEADERS.firstOrNull { upper.startsWith(it.first) }
}

private fun sectionForNumber(number: Int): VmSection = when (number) {
    in 1..3 -> VmSection.TREASURES
    in 4..6 -> VmSection.MINISTRY
    in 7..8 -> VmSection.CHRISTIAN_LIFE
    else -> VmSection.MINISTRY
}

/**
 * Resultado do parse de UMA linha de parte (único para os dois caminhos).
 *
 * `tail` é o texto que sobra na mesma linha depois dos minutos. É ele que
 * antes se perdia no caminho com cabeçalho (ex.: "DE CASA EM CASA").
 */
internal data class ParsedPartLine(
    val number: Int,
    val title: String,
    val minutes: Int?,
    val tail: String,
)

/**
 * Interpreta `N. Título (M min) cauda` / `N) Título M min cauda` / `N. Título`.
 * Aceita parênteses, minutos sem parênteses e "min." — sempre escolhendo a
 * PRIMEIRA ocorrência de minutos. Puro/testável.
 */
internal fun parsePartLine(line: String): ParsedPartLine? {
    val trimmed = line.trim()
    val prefix = PART_PREFIX_RE.matchEntire(trimmed) ?: return null
    val number = prefix.groupValues[1].toIntOrNull() ?: return null
    val rest = prefix.groupValues[2].trim()
    if (rest.isEmpty()) return ParsedPartLine(number, "", null, "")

    val paren = PAREN_MIN_RE.find(rest)
    val bare = BARE_MIN_RE.find(rest)
    val match = listOfNotNull(paren, bare).minByOrNull { it.range.first }

    var title: String
    var minutes: Int?
    var tail: String
    if (match != null) {
        title = rest.substring(0, match.range.first)
        minutes = match.groupValues[1].toIntOrNull()
        tail = rest.substring(match.range.last + 1)
    } else {
        title = rest
        minutes = null
        tail = ""
    }

    title = title.replace(EMPTY_PARENS_RE, "")
        .trim()
        .trim('(', ')', '-', '–', '—', ':', '.', ',', ' ')
        .replace(MULTI_SPACE_RE, " ")
        .trim()
    tail = tail.trim().trimStart('-', '–', '—', ':', '.', ',', ' ').trim()
    // Só título e cauda? A cauda é o título (parte sem título explícito).
    if (title.isBlank() && tail.isNotBlank()) {
        title = tail
        tail = ""
    }
    return ParsedPartLine(number, title, minutes, tail)
}

/** Título/minutos de uma linha sem número de parte (título no corpo). */
private fun titleAndMinutes(line: String): Pair<String, Int?> {
    val m = MIN_RE.find(line) ?: return line.trim() to null
    val cleaned = line.removeRange(m.range)
        .replace(EMPTY_PARENS_RE, "")
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
    if (lines.any { l -> headerOf(l) != null }) return null
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

/** Construtor de uma parte enquanto as linhas de corpo são coletadas. */
private class PartBuilder(
    val section: VmSection,
    val number: Int,
    var title: String,
    var minutes: Int?,
) {
    val body = mutableListOf<String>()

    fun build(): VmPart? = buildPart(
        section = section,
        number = number,
        title = title,
        minutes = minutes,
        bodyLines = body,
    )
}

/**
 * Natureza: o mapeamento seção+número manda (igual nos dois caminhos);
 * rótulos no corpo são fallback para números fora da tabela.
 */
private fun classifyNature(
    section: VmSection,
    number: Int,
    title: String,
    body: String,
): VmNature? {
    // A classificação por título+rótulo manda; o fallback abaixo só existe
    // para títulos fora da tabela (ex.: descrição no lugar do tipo da parte).
    vmNatureOf(section, number, title, body)?.let { return it }
    val lower = body.lowercase()
    val normalized = normalizeText(body)
    return when {
        normalized.contains("consideracao") -> VmNature.CONSIDERATION
        normalized.contains("demonstracao") -> VmNature.DEMO
        normalized.contains("discurso") -> VmNature.TALK
        // Papéis do diálogo são um sinal forte de demonstração.
        lower.contains("publicador:") || lower.contains("morador:") -> VmNature.DEMO
        else -> null
    }
}

/** Materializa uma [VmPart] a partir do corpo coletado. */
private fun buildPart(
    section: VmSection,
    number: Int,
    title: String,
    minutes: Int?,
    bodyLines: List<String>,
): VmPart? {
    val cleanTitle = title.trim()
    val body = bodyLines.joinToString("\n").trim()
    val nature = classifyNature(section, number, cleanTitle, body) ?: return null
    val partType = VM_PART_TYPES.firstOrNull { cleanTitle.startsWith(it, ignoreCase = true) }
    return VmPart(
        section = section,
        partNumber = number,
        title = cleanTitle,
        minutes = minutes ?: 0,
        nature = nature,
        body = body,
        scenario = vmScenarioOf(cleanTitle + "\n" + body),
        partType = partType,
        reference = vmReferenceOf(body.ifBlank { cleanTitle }),
    )
}

/** A linha seguinte confirma minutos (usado para iniciar parte sem "(N min)"). */
private fun startsPartWithoutMinutes(
    parsed: ParsedPartLine,
    nextNonEmpty: String?,
): Boolean {
    if (nextNonEmpty != null && TIME_ONLY_RE.matches(nextNonEmpty)) return true
    return VM_PART_TYPES.any { parsed.title.startsWith(it, ignoreCase = true) } ||
        VM_SCENARIOS.any { parsed.title.contains(it, ignoreCase = true) } ||
        VM_LABELS.any { parsed.title.contains(it, ignoreCase = true) }
}

/** Uma linha numerada é uma NOVA parte dentro da seção? */
private fun isNewPart(
    parsed: ParsedPartLine,
    current: PartBuilder?,
    nextNonEmpty: String?,
): Boolean {
    if (current == null) return true
    // Partes da apostila são ascendentes; listas numeradas do corpo (1,2,3)
    // não podem "reabrir" uma parte anterior.
    if (parsed.number <= current.number) return false
    if (parsed.minutes != null) return true
    return startsPartWithoutMinutes(parsed, nextNonEmpty)
}

/**
 * Caminho com cabeçalho de seção: percorre TODAS as linhas, aceita múltiplas
 * partes por seção e usa [parsePartLine] para título/minutos/cauda.
 */
private fun detectFromHeaders(text: String): List<VmPart> {
    val out = mutableListOf<VmPart>()
    val lines = text.split("\n")
    var section: VmSection? = null
    var current: PartBuilder? = null

    fun flush() {
        current?.build()?.let { out += it }
        current = null
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()

        val header = if (line.isEmpty()) null else headerOf(line)
        if (header != null) {
            flush()
            section = header.second
            val remainder = line.substring(header.first.length)
            val glued = parsePartLine(remainder)
            if (glued != null) {
                current = PartBuilder(header.second, glued.number, glued.title, glued.minutes)
                if (glued.tail.isNotBlank()) current!!.body += glued.tail
            } else {
                // Cabeçalho + "ponto N" (o título pode vir na linha seguinte).
                val inline = INLINE_POINT_RE.find(line)
                if (inline != null) {
                    val n = inline.groupValues[1].toIntOrNull() ?: 1
                    val m = MIN_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    current = PartBuilder(header.second, n, "", m)
                }
            }
            i++
            continue
        }

        val sec = section
        if (sec == null) {
            i++
            continue
        }

        if (line.isEmpty()) {
            current?.body?.add(raw)
            i++
            continue
        }

        val parsed = parsePartLine(line)
        val nextNonEmpty = lines.asSequence().drop(i + 1)
            .firstOrNull { it.isNotBlank() }?.trim()
        if (parsed != null && isNewPart(parsed, current, nextNonEmpty)) {
            flush()
            current = PartBuilder(sec, parsed.number, parsed.title, parsed.minutes)
            if (parsed.tail.isNotBlank()) current!!.body += parsed.tail
            i++
            continue
        }

        val cur = current
        if (cur == null) {
            // A3: cabeçalho + "Título: X (N min)" sem número — parte 1.
            val titulo = TITULO_RE.find(line)
            if (titulo != null) {
                current = PartBuilder(
                    sec, 1, titulo.groupValues[1].trim(),
                    titulo.groupValues[2].toIntOrNull(),
                )
            }
            i++
            continue
        }

        if (cur.title.isBlank()) {
            if (TIME_ONLY_RE.matches(line)) {
                cur.minutes = cur.minutes ?: MIN_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                i++
                continue
            }
            if (TOPICO_LINE_RE.containsMatchIn(line)) {
                cur.body += raw
                i++
                continue
            }
            val (t, m) = titleAndMinutes(line)
            cur.title = t
            cur.minutes = cur.minutes ?: m
            i++
            continue
        }

        if (cur.minutes == null && TIME_ONLY_RE.matches(line)) {
            cur.minutes = MIN_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            i++
            continue
        }

        cur.body += raw
        i++
    }
    flush()
    return out
}

/** Sinal V&M em um trecho (tipo/cenário/rótulo/contexto/papéis). */
private fun hasPartSignal(text: String): Boolean {
    val lower = text.lowercase()
    return VM_PART_TYPES.any { lower.contains(it.lowercase()) } ||
        VM_SCENARIOS.any { lower.contains(it.lowercase()) } ||
        VM_LABELS.any { lower.contains(it) } ||
        VM_CONTEXT_MARKERS.any { lower.contains(it) } ||
        VM_NATURE_MARKERS.any { lower.contains(it) }
}

/** O trecho tem pelo menos 2 refs (Bíblia + publicação) no corpo? */
private fun hasRefSignal(text: String): Boolean {
    val (bible, pubs) = extractRefsFromBody(text)
    return bible.size + pubs.size >= 2
}

/**
 * Título + `(N min)` no mesmo bloco, com ou sem quebras de linha:
 * cobre o Formato 5 (tudo inline) e os Formatos 1/4 depois que o
 * [PasteTextNormalizer] junta `(N min)` à linha do título.
 *
 * Só aceita com `(N min)` entre parênteses, título curto, 3–15 min, ≥2 sinais
 * de ref (Bíblia + publicação). O sinal de publicação é a mitigação extra
 * contra texto livre com minutos e refs bíblicas esporádicas.
 */
private fun detectSingleLineVmPart(text: String): VmPart? {
    val time = PAREN_MIN_RE.find(text) ?: return null
    val minutes = time.groupValues[1].toIntOrNull() ?: return null
    if (minutes !in 3..15) return null
    val title = text.substring(0, time.range.first).trim()
    if (title.isEmpty() || title.length >= 80) return null
    val firstLine = title.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (firstLine.isBlank()) return null
    if (headerOf(firstLine) != null) return null
    // Formato numerado (2/3) tem tratamento próprio em detectLoose/headers.
    if (parsePartLine(firstLine) != null) return null
    val body = text.substring(time.range.last + 1).trim()
    if (body.isBlank()) return null
    if (PAREN_MIN_RE.containsMatchIn(body)) return null
    val (bible, pubs) = extractRefsFromBody(body)
    if (pubs.isEmpty()) return null
    // ≥2 sinais de ref: Bíblia reconhecida/abreviação comum OU 2 publicações.
    // "Sl 19:1" não está no mapa do RefDetector, mas é uma ref bíblica óbvia.
    val hasBible = bible.isNotEmpty() || BIBLE_REF_HINT_RE.containsMatchIn(body)
    if (!hasBible && pubs.size < 2) return null
    val (section, partNumber) = when (minutes) {
        10 -> VmSection.TREASURES to 1
        in 3..4 -> VmSection.MINISTRY to 4
        in 5..6 -> VmSection.MINISTRY to 6
        else -> VmSection.TREASURES to 1
    }
    return buildPart(section, partNumber, title, minutes, body.lines())
        ?: VmPart(section, partNumber, title, minutes, VmNature.TALK, body)
}

/**
 * Parte 1 de Tesouros colada SEM número e SEM cabeçalho:
 * `Título curto` + linha `(N min)` + corpo com ≥2 refs.
 *
 * Estrutura + minutos + refs — nunca keyword do título. Só aceita 10 min
 * (âncora da parte 1) ou 3–15 min (outras partes sem número), e rejeita
 * textos com outra linha de minutos (provável esboço S-34).
 */
private fun detectSinglePart(text: String): VmPart? {
    val lines = text.split("\n")
    val nonEmpty = lines.withIndex().filter { it.value.isNotBlank() }
    if (nonEmpty.size < 3) return null
    val title = nonEmpty[0].value.trim()
    if (title.isBlank() || title.length >= 80) return null
    if (headerOf(title) != null) return null
    // Formato numerado (2/3) tem tratamento próprio (parsePartLine/detectLoose).
    if (parsePartLine(title) != null) return null

    val timeLine = nonEmpty[1].value.trim()
    if (!TIME_ONLY_RE.matches(timeLine)) return null
    val minutes = MIN_RE.find(timeLine)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    if (minutes != 10 && minutes !in 3..15) return null

    val bodyStart = nonEmpty[1].index + 1
    val bodyLines = lines.drop(bodyStart)
    val body = bodyLines.joinToString("\n").trim()
    if (body.isBlank()) return null
    // Outra linha "(N min)" indica esboço com múltiplas seções, não parte única.
    if (bodyLines.any { TIME_ONLY_RE.matches(it.trim()) }) return null
    if (!hasRefSignal(body)) return null

    return buildPart(
        section = VmSection.TREASURES,
        number = 1,
        title = title,
        minutes = minutes,
        bodyLines = bodyLines,
    )
}

/**
 * Caminho SEM cabeçalho: varre TODAS as linhas (não só a primeira), aceita
 * múltiplas partes soltas e usa o mesmo [parsePartLine] do caminho com
 * cabeçalho. Exige sinal V&M para não sequestrar listas numeradas comuns.
 */
private fun detectLoose(text: String): List<VmPart> {
    val lines = text.split("\n")
    val parsedLines = lines.map { parsePartLine(it) }
    val candidates = parsedLines.indices.filter { i ->
        val p = parsedLines[i] ?: return@filter false
        p.number in 1..8
    }
    if (candidates.isEmpty()) return emptyList()

    // 1) filtra candidatos com sinal V&M na própria linha ou nas 3 seguintes
    //    (até o próximo candidato). Números devem ser ascendentes para não
    //    confundir listas numeradas do corpo com novas partes.
    val accepted = mutableListOf<Int>()
    var lastNumber = 0
    for (i in candidates) {
        val p = parsedLines[i]!!
        if (p.number <= lastNumber) continue
        val nextCandidate = candidates.firstOrNull { it > i } ?: lines.size
        val window = buildString {
            appendLine(p.title)
            appendLine(p.tail)
            lines.subList(i + 1, nextCandidate)
                .asSequence()
                .filter { it.isNotBlank() }
                .take(3)
                .forEach { appendLine(it) }
        }
        val nextNonEmpty = lines.asSequence().drop(i + 1)
            .firstOrNull { it.isNotBlank() }?.trim()
        val explicitMinutes = p.minutes ?: nextNonEmpty?.let {
            if (TIME_ONLY_RE.matches(it)) {
                MIN_RE.find(it)?.groupValues?.get(1)?.toIntOrNull()
            } else {
                null
            }
        }
        val plausibleMinutes = explicitMinutes != null &&
            (explicitMinutes == 10 || explicitMinutes in 3..15)
        // Estrutura (número 1–8 + minutos plausíveis + ≥2 refs) vale mesmo
        // sem keyword no título. Restrito a minutos na LINHA SEGUINTE ou a um
        // único candidato: um esboço S-34 tem várias seções numeradas com
        // minutos na própria linha e refs — não pode virar parte V&M.
        val structural = plausibleMinutes && hasRefSignal(window) &&
            (p.minutes == null || candidates.size == 1)
        if (!hasPartSignal(window) && !structural) continue
        accepted += i
        lastNumber = p.number
    }
    if (accepted.isEmpty()) return emptyList()

    // 2) corpo de cada parte vai até o próximo candidato aceito
    val out = mutableListOf<VmPart>()
    accepted.forEachIndexed { idx, i ->
        val p = parsedLines[i]!!
        val end = accepted.getOrNull(idx + 1) ?: lines.size
        val bodyLines = mutableListOf<String>()
        if (p.tail.isNotBlank()) bodyLines += p.tail
        var title = p.title
        var minutes = p.minutes
        for (j in i + 1 until end) {
            val l = lines[j].trim()
            if (l.isEmpty()) {
                bodyLines += lines[j]
                continue
            }
            if (minutes == null && TIME_ONLY_RE.matches(l)) {
                minutes = MIN_RE.find(l)?.groupValues?.get(1)?.toIntOrNull()
                continue
            }
            if (title.isBlank()) {
                title = l
                continue
            }
            bodyLines += lines[j]
        }
        val section = sectionForNumber(p.number)
        buildPart(section, p.number, title, minutes, bodyLines)?.let { out += it }
    }
    return out
}

/** Detecta material V&M; vazio quando não parece V&M. */
fun detectVmMaterial(text: String): List<VmPart> {
    val normalized = PasteTextNormalizer.normalize(text)
    if (normalized.isBlank()) return emptyList()
    // #24-fix A1: o formato real ("Título:" + "Tópico N:") não tem cabeçalho.
    titleFirstMaterial(normalized)?.let { return listOf(it) }
    // Formato 5: tudo inline, sem quebra de linha.
    detectSingleLineVmPart(normalized)?.let { return listOf(it) }
    // Parte única sem número/cabeçalho: título curto + "(N min)" + refs.
    detectSinglePart(normalized)?.let { return listOf(it) }
    return if (normalized.lineSequence().any { headerOf(it) != null }) {
        detectFromHeaders(normalized)
    } else {
        detectLoose(normalized)
    }
}

/** #8b: roteamento do paste de material V&M. */
sealed interface VmPastePlan {
    /** Discurso: gera a partir da primeira parte TALK; as demais são ignoradas. */
    data class Talk(val talk: VmPart, val ignored: List<VmPart>) : VmPastePlan

    /** S-38: demonstração (diálogo) — aceita quando não há TALK no material. */
    data class Demo(val demo: VmPart, val ignored: List<VmPart>) : VmPastePlan

    /**
     * Consideração (ex.: "O que você diria?"): UM card único, sem a estrutura
     * de 3 cards do discurso.
     */
    data class Consideration(
        val consideration: VmPart,
        val ignored: List<VmPart>,
    ) : VmPastePlan

    /** Material V&M sem TALK, DEMO ou CONSIDERATION: rejeição limpa. */
    data class Rejected(val parts: List<VmPart>) : VmPastePlan

    /** Não é V&M: fluxo genérico (S-34/candidatos) intacto. */
    object Generic : VmPastePlan
}

fun vmPastePlan(text: String): VmPastePlan {
    val parts = detectVmMaterial(text)
    if (parts.isEmpty()) return VmPastePlan.Generic
    parts.firstOrNull { it.nature == VmNature.TALK }?.let { talk ->
        return VmPastePlan.Talk(talk, parts - talk)
    }
    // S-38: sem discurso, uma demonstração é aceita — inclusive colada sem o
    // cabeçalho da seção (detecção solta "5. Título (4 min)" + cenário).
    parts.firstOrNull { it.nature == VmNature.DEMO }?.let { demo ->
        return VmPastePlan.Demo(demo, parts - demo)
    }
    // Consideração: card único (ex.: "O que você diria?").
    parts.firstOrNull { it.nature == VmNature.CONSIDERATION }?.let { consideration ->
        return VmPastePlan.Consideration(consideration, parts - consideration)
    }
    return VmPastePlan.Rejected(parts)
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

/** Mensagem do chat quando há DEMO (com o resto ignorado, se houver). */
fun vmDemoMessage(demo: VmPart, ignored: List<VmPart>): String = buildString {
    append("Reconheci material V&M.\n")
    append(
        "✓ Parte ${demo.partNumber} (${demo.section.label()}): ${demo.title} " +
            "(${demo.minutes} min) — vou gerar a demonstração (diálogo)."
    )
    if (ignored.isNotEmpty()) {
        append("\n\nIgnorei: ")
        append(ignored.joinToString("; ") { "parte ${it.partNumber} — ${it.nature.label()}" })
        append(".")
    }
}

/** Mensagem do chat quando há CONSIDERATION (com o resto ignorado). */
fun vmConsiderationMessage(
    consideration: VmPart,
    ignored: List<VmPart>,
): String = buildString {
    append("Reconheci material V&M.\n")
    append(
        "✓ Parte ${consideration.partNumber} (${consideration.section.label()}): " +
            "${consideration.title} (${consideration.minutes} min) — vou preparar a consideração."
    )
    if (ignored.isNotEmpty()) {
        append("\n\nIgnorei: ")
        append(ignored.joinToString("; ") { "parte ${it.partNumber} — ${it.nature.label()}" })
        append(".")
    }
}

/** Mensagem do chat quando nenhuma parte gera (sem discurso/demo/consideração). */
fun vmRejectionMessage(parts: List<VmPart>): String = buildString {
    // Mantém a frase histórica consumida pelos testes/UX; as considerações
    // entram na lista de partes e na explicação final.
    append("Reconheci material V&M, mas nenhuma das partes gera discurso ou demonstração:\n")
    parts.forEach { p ->
        append("- ${p.section.label()} parte ${p.partNumber} — ${p.nature.label()}\n")
    }
    append(
        "\nO app processa discursos (TESOUROS 1, NOSSA VIDA 7, FAÇA SEU MELHOR " +
            "parte 6 quando é discurso), demonstrações (FAÇA SEU MELHOR 4-6) " +
            "e considerações (O que você diria?)."
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

/** Seção BODY que materializa a parte V&M colada (TALK ou DEMO) — fonte do gerador. */
fun vmPartSection(
    part: VmPart,
    noteId: String,
    order: Int,
    id: String,
    now: Long,
): SpeechSection {
    // #24-fix C: as refs do corpo viram refs estruturadas da seção — o dossiê
    // do chat sobre a seção TALK funciona antes do apply.
    val (bibleRefs, publicationRefs) = extractRefsFromBody(part.body)
    return SpeechSection(
        id = id,
        noteId = noteId,
        order = order,
        role = SectionRole.BODY,
        title = part.title,
        minutes = part.minutes,
        contentHtml = htmlFromText(part.body),
        bibleRefs = bibleRefs,
        publicationRefs = publicationRefs,
        methodPrinciple = null,
        createdAt = now,
        updatedAt = now,
    )
}
