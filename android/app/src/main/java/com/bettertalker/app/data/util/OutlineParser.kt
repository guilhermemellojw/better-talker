package com.bettertalker.app.data.util

/**
 * Esboço-base do discurso: seções ordenadas com minutos opcionais.
 * Duas origens convergem aqui: PDF/DOCX estruturado ((N min)) e texto colado.
 */
data class OutlineSection(
    val title: String,
    val minutes: Int? = null,
    val order: Int = 0,
    /** Corpo integral entre este tópico e o próximo (subtópicos, refs). */
    val body: String = "",
    /** Nível hierárquico (0 = raiz; indentação manda). */
    val level: Int = 0
)

data class ParsedOutline(
    val title: String,
    val totalMinutes: Int? = null,
    val sections: List<OutlineSection>,
    /** Texto antes da primeira seção (cabeçalho, NOTA:). */
    val preamble: String = ""
)

/** Parser de esboços estruturados (PDF/DOCX com "(N min)"). */
object OutlineParser {
    private val MIN_RE = Regex("""[\[(]\s*(\d+)\s*(min\.?|minutos?)\s*[\])]""", RegexOption.IGNORE_CASE)
    private val ORPHAN_MIN_RE = Regex("""^[\[(]\s*(\d+)\s*(min\.?|minutos?)\s*[\])]\s*$""", RegexOption.IGNORE_CASE)
    private val TOTAL_RE = Regex("""TEMPO\s*TOTAL\s*:\s*(\d+)\s*MINUTOS?""", RegexOption.IGNORE_CASE)
    private val NOISE_RE = Regex("""^(N\.º|©|\(|S-\d+)""")

    fun parse(text: String, fileName: String): ParsedOutline {
        val rawLines = text.split("\n").map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        // 1) junta "(N min)" órfão (quebra de linha do PDF/DOCX) à linha anterior
        val lines = mutableListOf<String>()
        for (line in rawLines) {
            val orphan = ORPHAN_MIN_RE.find(line)
            if (orphan != null && lines.isNotEmpty()) {
                lines[lines.size - 1] = (lines.last() + " " + orphan.value.trim()).trim()
            } else {
                lines += line
            }
        }
        val sections = mutableListOf<OutlineSection>()
        val bodies = mutableListOf<StringBuilder>()
        val preamble = StringBuilder()
        var total: Int? = null
        var title = ""
        var prevLine = ""
        var prevConsumed = true

        fun bodyTarget(): StringBuilder? =
            if (bodies.size == sections.size && sections.isNotEmpty()) bodies.last() else null

        for (line in lines) {
            if (TOTAL_RE.find(line) != null) {
                total = TOTAL_RE.find(line)!!.groupValues[1].toIntOrNull()
                prevLine = line
                prevConsumed = true
                continue
            }
            val m = MIN_RE.find(line)
            if (m != null && line.indexOf(m.value) > 0) {
                var t = line.substring(0, line.indexOf(m.value)).trim().trimEnd(':')
                val min = m.groupValues[1].toIntOrNull()
                // evita falso positivo no meio de frase comum ("fale por (5 min) sobre…"):
                // exige minutos no fim da linha ou título com cara de cabeçalho
                val atEnd = line.trimEnd().endsWith(m.value)
                if (!atEnd && !isHeadingLike(t)) {
                    (bodyTarget() ?: preamble).append(line).append('\n')
                    prevLine = line
                    prevConsumed = false
                    continue
                }
                // título quebrado na linha anterior (CAIXA ALTA típica de esboço):
                // ela já foi para o corpo — remove de lá antes de juntar ao título
                if (!prevConsumed && isHeadingLike(prevLine) && t.length < 60) {
                    val target = bodyTarget() ?: preamble
                    val s = target.toString()
                    if (s.endsWith(prevLine + "\n")) target.setLength(s.length - prevLine.length - 1)
                    t = (prevLine + " " + t).trim()
                }
                if (t.length >= 3 && min != null) {
                    sections += OutlineSection(t, min, sections.size)
                    bodies += StringBuilder()
                    // resto da linha após o marcador também é corpo
                    val rest = line.substring(line.indexOf(m.value) + m.value.length).trim()
                    if (rest.isNotEmpty()) bodies.last().append(rest).append('\n')
                    prevLine = line
                    prevConsumed = true
                    continue
                }
            }
            // corpo integral: tudo entre um tópico e outro é preservado
            (bodyTarget() ?: preamble).append(line).append('\n')
            prevLine = line
            prevConsumed = false
        }
        // título = primeira linha de conteúdo (ignora cabeçalhos tipo N.º/©)
        title = rawLines.firstOrNull { l ->
            l.length > 10 && !NOISE_RE.containsMatchIn(l) && MIN_RE.find(l) == null &&
                TOTAL_RE.find(l) == null
        } ?: fileName.substringBeforeLast('.')
        val final = sections.mapIndexed { i, s ->
            s.copy(order = i, body = bodies.getOrNull(i)?.toString()?.trim().orEmpty())
        }
        return ParsedOutline(title.take(140), total, final, preamble.toString().trim())
    }

    /** Linha com cara de título de esboço: maioria maiúscula, sem pontuação final. */
    private fun isHeadingLike(line: String): Boolean {
        if (line.length !in 4..120) return false
        val last = line.last()
        if (last == '.' || last == '?' || last == '!' || last == ':') return false
        if (MIN_RE.containsMatchIn(line) || TOTAL_RE.containsMatchIn(line)) return false
        val letters = line.filter { it.isLetter() }
        if (letters.length < 4) return false
        return letters.count { it.isUpperCase() } * 2 >= letters.length
    }

    fun sumMinutes(sections: List<OutlineSection>): Int =
        sections.mapNotNull { it.minutes }.sum()

    fun toJson(sections: List<OutlineSection>, preamble: String = ""): String {
        // JSON manual: org.json não existe em testes unitários JVM
        val arr = sections.joinToString(",", "[", "]") {
            "{\"t\":\"${esc(it.title)}\",\"m\":${it.minutes ?: -1}," +
                "\"b\":\"${esc(it.body)}\",\"l\":${it.level}}"
        }
        return "{\"preamble\":\"${esc(preamble)}\",\"sections\":$arr}"
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Envelope novo; aceita o array legado sem preâmbulo/body. */
    fun parseEnvelope(json: String): Pair<String, List<OutlineSection>> {
        return try {
            val t = json.trim()
            if (t.startsWith("{")) {
                val preRe = Regex(""""preamble":"((?:[^"\\]|\\.)*)"""")
                val preamble = preRe.find(t)?.let { unesc(it.groupValues[1]) } ?: ""
                val arrRe = Regex(""""sections":(\[.*\])\s*\}$""", RegexOption.DOT_MATCHES_ALL)
                val arr = arrRe.find(t)?.groupValues?.get(1) ?: "[]"
                preamble to parseArray(arr)
            } else {
                "" to parseArray(t)
            }
        } catch (_: Exception) {
            "" to emptyList()
        }
    }

    fun fromJson(json: String): List<OutlineSection> = parseEnvelope(json).second

    private fun parseArray(arr: String): List<OutlineSection> {
        return try {
            // formato com "b"/"l" explícitos ou legados sem body/level
            val full = Regex("""\{"t":"((?:[^"\\]|\\.)*)","m":(-?\d+),"b":"((?:[^"\\]|\\.)*)","l":(\d+)\}""")
            val withBody = Regex("""\{"t":"((?:[^"\\]|\\.)*)","m":(-?\d+),"b":"((?:[^"\\]|\\.)*)"\}""")
            val legacy = Regex("""\{"t":"((?:[^"\\]|\\.)*)","m":(-?\d+)\}""")
            val use = when {
                full.containsMatchIn(arr) -> 2
                withBody.containsMatchIn(arr) -> 1
                else -> 0
            }
            val re = listOf(legacy, withBody, full)[use]
            re.findAll(arr).mapIndexed { i, m ->
                val title = unesc(m.groupValues[1])
                val mm = m.groupValues[2].toIntOrNull() ?: -1
                val body = if (use >= 1) {
                    try {
                        unesc(m.groupValues[3])
                    } catch (_: Exception) {
                        ""
                    }
                } else ""
                val level = if (use >= 2) m.groupValues[4].toIntOrNull() ?: 0 else 0
                OutlineSection(title, if (mm < 0) null else mm, i, body, level)
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Análise de esboço colado: cada frase vira tópico candidato + fusões sugeridas. */
object PastedOutlineAnalyzer {
    data class Candidate(val text: String, val key: Int, val suggested: Boolean = true, val level: Int = 0)
    data class MergeSuggestion(val a: Int, val b: Int, val similarity: Double)

    // descartados de verdade: só estrutura, nunca conteúdo do usuário
    private val HARD_DROP_RES = listOf(
        Regex("^©"),
        Regex("TEMPO\\s*TOTAL", RegexOption.IGNORE_CASE),
        Regex("^N\\.\\S\\s+\\d+"),
        Regex("^S-\\d+")
    )
    // mantidos, mas desmarcados: provavelmente não são tópicos (usuário decide)
    private val SOFT_NOISE_RES = listOf(
        Regex("^\\[.*\\]$"), // [instruções]
        Regex("^\\(?[A-ZÀ-Þ][a-zà-þ]{1,4}\\s+\\d+:\\d+") // (Gên 1:26) sozinho
    )
    /** Linha que é só parênteses (com ponto final opcional): gruda no tópico anterior. */
    private val PAREN_ONLY_RE = Regex("^\\(.*\\)[.!?]?$")

    private data class Seg(val text: String, val indent: Int)

    fun candidates(text: String): Pair<List<Candidate>, Int> {
        var dropped = 0
        val segs = mutableListOf<Seg>()
        for (rawLine in text.split("\n")) {
            // mede indentação ANTES de normalizar (tab = 4)
            val indent = rawLine.takeWhile { it == ' ' || it == '\t' }
                .fold(0) { acc, c -> acc + if (c == '\t') 4 else 1 }
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty()) continue
            if (HARD_DROP_RES.any { it.containsMatchIn(line) }) {
                dropped++
                continue
            }
            line.split(Regex("(?<=[.!?])\\s+")).forEach { s0 ->
                val s = s0.trim()
                // tópicos curtos valem ("Oração"); só cai vazio/número/pontuação pura
                if (s.length > 2 && s.any { c -> c.isLetter() }) segs += Seg(s, indent)
            }
        }
        // nível = posição do recuo entre os distintos (robusto a 2 ou 4 espaços)
        val levels = segs.map { it.indent }.distinct().sorted()
        val out = mutableListOf<Candidate>()
        for (seg in segs) {
            if (PAREN_ONLY_RE.matches(seg.text) && out.isNotEmpty()) {
                val last = out.last()
                out[out.size - 1] = last.copy(text = "${last.text} ${seg.text}")
                continue
            }
            val level = levels.indexOf(seg.indent).coerceAtLeast(0)
            // título = frase integral (refs preservadas); só limita tamanho
            val title = if (seg.text.length > 140) seg.text.take(137).trimEnd() + "…" else seg.text
            val soft = SOFT_NOISE_RES.any { it.containsMatchIn(seg.text) }
            out += Candidate(title, out.size, suggested = !soft, level = level)
        }
        return out to dropped
    }

    /** Similaridade Jaccard sobre palavras normalizadas. */
    fun similarity(a: String, b: String): Double {
        val wa = normalizeText(a).split(" ").filter { it.length > 2 }.toSet()
        val wb = normalizeText(b).split(" ").filter { it.length > 2 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        val inter = wa.intersect(wb).size.toDouble()
        return inter / (wa.size + wb.size - inter)
    }

    fun suggestMerges(cands: List<Candidate>, threshold: Double = 0.55): List<MergeSuggestion> {
        val out = mutableListOf<MergeSuggestion>()
        for (i in cands.indices) for (j in i + 1 until cands.size) {
            val s = similarity(cands[i].text, cands[j].text)
            if (s >= threshold) out += MergeSuggestion(i, j, s)
        }
        return out.sortedByDescending { it.similarity }
    }

    fun mergeTitles(a: String, b: String): String {
        val t = "$a / $b"
        return if (t.length > 120) t.take(117).trimEnd() + "…" else t
    }
}

/** Títulos ## da nota (destinos de inserção). */
fun headingsOf(md: String): List<String> =
    md.lines().mapNotNull { line ->
        val t = line.trimStart()
        // ##, ###, … (níveis do esboço)
        val m = Regex("^(#{2,6})\\s+(.*)$").find(t) ?: return@mapNotNull null
        m.groupValues[2].trim().take(120).ifEmpty { null }
    }.distinct()

/** Score de casamento título buscado x título da linha: 2 exato, 1 contém, 0 nada. */
private fun headingScore(hNorm: String, target: String): Int {
    if (hNorm.isEmpty() || target.isEmpty()) return 0
    return when {
        hNorm == target -> 2
        hNorm.contains(target) || target.contains(hNorm) -> 1
        else -> 0
    }
}

/** Título ##… da linha (qualquer nível) ou null. */
private fun headingTitleOf(line: String): String? {
    val t = line.trimStart()
    val m = Regex("^(#{2,6})\\s+(.*)$").find(t) ?: return null
    return m.groupValues[2].trim().take(120).ifEmpty { null }
}

/** Melhor linha de título para o destino: exata primeiro, depois contém. */
private fun bestHeadingLine(lines: List<String>, normTarget: String): Int {
    var bestIdx = -1
    var bestScore = 0
    lines.forEachIndexed { i, line ->
        val ht = headingTitleOf(line) ?: return@forEachIndexed
        val s = headingScore(normalizeText(ht), normTarget)
        if (s > bestScore) {
            bestScore = s
            bestIdx = i
        }
    }
    return bestIdx
}

/**
 * Offset (índice de inserção) logo após o título indicado, pulando brancos.
 * Null se não achar. Puro/testável — usado para inserir na árvore viva.
 */
fun headingOffset(text: String, heading: String): Int? {
    val normTarget = normalizeText(heading)
    if (normTarget.isBlank()) return null
    val lines = text.split("\n")
    val idx = bestHeadingLine(lines, normTarget)
    if (idx < 0) return null
    var offset = lines.subList(0, idx).sumOf { it.length + 1 }
    var p = offset + lines[idx].length
    if (p < text.length && text[p] == '\n') p++
    while (p < text.length) {
        val nl = text.indexOf('\n', p)
        val end = if (nl == -1) text.length else nl
        if (text.substring(p, end).isBlank()) p = if (nl == -1) text.length else nl + 1
        else break
    }
    return p.coerceAtMost(text.length)
}

/** Esqueleto markdown do esboço (pré-preenchimento / reinserção). Puro/testável. */
fun skeletonMarkdown(sections: List<OutlineSection>, preamble: String = ""): String {
    val parts = mutableListOf<String>()
    if (preamble.isNotBlank()) parts += preamble
    sections.forEach {
        // nível vira profundidade do título (##, ###, …) — hierarquia visível
        val marks = "#".repeat((it.level + 2).coerceAtMost(6))
        val head = "$marks " + it.title + (if (it.minutes != null) " (${it.minutes} min)" else "")
        parts += if (it.body.isNotBlank()) "$head\n\n${it.body}" else head
    }
    return parts.joinToString("\n\n")
}

/**
 * Insere bloco sob o título ## indicado.
 * Retorna (novoTexto, cursorOffset). Título ausente => anexa no fim.
 */
fun insertUnder(md: String, heading: String?, block: String): Pair<String, Int> {
    val clean = block.trim()
    if (heading.isNullOrBlank()) {
        val t = (md.trimEnd() + "\n\n" + clean).trim()
        return t to t.length
    }
    val normTarget = normalizeText(heading)
    val lines = md.split("\n").toMutableList()
    val idx = bestHeadingLine(lines, normTarget)
    if (idx < 0) {
        val t = (md.trimEnd() + "\n\n" + clean).trim()
        return t to t.length
    }
    var at = idx + 1
    while (at < lines.size && lines[at].isBlank()) at++
    lines.add(at, clean)
    lines.add(at + 1, "")
    val t = lines.joinToString("\n").trimEnd()
    val cursor = lines.subList(0, at + 1).joinToString("\n").length.coerceAtMost(t.length)
    return t to cursor
}
