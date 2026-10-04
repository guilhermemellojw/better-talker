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
    /**
     * Orientações gerais do orador (F3.x): texto antes da primeira seção
     * (NOTA:) + orientações finais (linhas `[...]` no fim do último tópico).
     * NÃO é introdução — é material de apoio ao orador.
     */
    val preamble: String = ""
)

/** Parser de esboços estruturados (PDF/DOCX com "(N min)"). */
object OutlineParser {
    /** Marcador de tempo de seção; internal para reuso pelo S34Detector (sem duplicar). */
    internal val MIN_RE = Regex("""[\[(]\s*(\d+)\s*(min\.?|minutos?)\s*[\])]""", RegexOption.IGNORE_CASE)
    private val ORPHAN_MIN_RE = Regex("""^[\[(]\s*(\d+)\s*(min\.?|minutos?)\s*[\])]\s*$""", RegexOption.IGNORE_CASE)
    private val TOTAL_RE = Regex("""TEMPO\s*TOTAL\s*:\s*(\d+)\s*MINUTOS?""", RegexOption.IGNORE_CASE)
    private val NOISE_RE = Regex("""^(N\.º|©|\(|S-\d+)""")

    /** Rodapé/metadados do S-34 (© …, S-34-T …) — nunca viram conteúdo. */
    private val FOOTER_RE = Regex("""^(©|S-\d)""")

    /** Linha composta só de blocos `[...]` (instrução ao orador). */
    private val BRACKET_ONLY_RE = Regex("""^(?:\s*\[[^\]]*\]\s*)+$""")
    private val BRACKET_INNER_RE = Regex("""\[([^\]]*)\]""")

    /** Número do esboço no heading: "N.º 35", "N.° 84", "N.º 26". */
    private val OUTLINE_NUMBER_RE = Regex("""^N\.[º°]\s*(\d+)\b""", RegexOption.IGNORE_CASE)

    /** Prefixo de NOTA ao orador: "NOTA:" ou "Nota ao orador:". */
    private val SPEAKER_NOTE_RE = Regex("""^(?:NOTA|Nota ao orador)\s*:""", RegexOption.IGNORE_CASE)

    /**
     * Filtro de "ruído" para a linha do TEMA (não confundir com NOISE_RE,
     * que continua intacto por compatibilidade).
     * AQUI NÃO inclui "N.º" — a linha do tema pode começar com "N.º 35".
     */
    private val TITLE_NOISE_RE = Regex("""^(©|\(|S-\d+)""")

    fun parse(text: String, fileName: String): ParsedOutline {
        // preserva recuo original (tab = 4, teto 8) para o corpo; matching usa texto limpo
        data class RLine(val indent: Int, val text: String)
        val rawLines = text.split("\n").mapNotNull { raw ->
            val indent = raw.takeWhile { it == ' ' || it == '\t' }
                .fold(0) { acc, c -> acc + if (c == '\t') 4 else 1 }
            val clean = raw.trim().replace(Regex("\\s+"), " ")
            if (clean.isEmpty()) null else RLine(indent, clean)
        }
        // 1) junta "(N min)" órfão (quebra de linha do PDF/DOCX) à linha anterior
        val lines = mutableListOf<RLine>()
        for (line in rawLines) {
            val orphan = ORPHAN_MIN_RE.find(line.text)
            if (orphan != null && lines.isNotEmpty()) {
                val prev = lines.last()
                lines[lines.size - 1] = prev.copy(
                    text = (prev.text + " " + orphan.value.trim()).trim()
                )
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
        var lastAppended = ""

        fun indented(r: RLine): String =
            " ".repeat(r.indent.coerceIn(0, 8)) + r.text

        fun bodyTarget(): StringBuilder? =
            if (bodies.size == sections.size && sections.isNotEmpty()) bodies.last() else null

        fun toBody(r: RLine) {
            val s = indented(r)
            (bodyTarget() ?: preamble).append(s).append('\n')
            lastAppended = s
        }

        for (line in lines) {
            val t0 = line.text
            if (TOTAL_RE.find(t0) != null) {
                total = TOTAL_RE.find(t0)!!.groupValues[1].toIntOrNull()
                prevLine = t0
                prevConsumed = true
                continue
            }
            val m = MIN_RE.find(t0)
            if (m != null && t0.indexOf(m.value) > 0) {
                var t = t0.substring(0, t0.indexOf(m.value)).trim().trimEnd(':')
                val min = m.groupValues[1].toIntOrNull()
                // evita falso positivo no meio de frase comum ("fale por (5 min) sobre…"):
                // exige minutos no fim da linha ou título com cara de cabeçalho
                val atEnd = t0.trimEnd().endsWith(m.value)
                if (!atEnd && !isHeadingLike(t)) {
                    toBody(line)
                    prevLine = t0
                    prevConsumed = false
                    continue
                }
                // título quebrado na linha anterior (CAIXA ALTA típica de esboço):
                // ela já foi para o corpo — remove de lá antes de juntar ao título
                if (!prevConsumed && isHeadingLike(prevLine) && t.length < 60) {
                    val target = bodyTarget() ?: preamble
                    val s = target.toString()
                    if (s.endsWith(lastAppended + "\n")) {
                        target.setLength(s.length - lastAppended.length - 1)
                    }
                    t = (prevLine + " " + t).trim()
                }
                if (t.length >= 3 && min != null) {
                    sections += OutlineSection(t, min, sections.size)
                    bodies += StringBuilder()
                    // resto da linha após o marcador também é corpo
                    val rest = t0.substring(t0.indexOf(m.value) + m.value.length).trim()
                    if (rest.isNotEmpty()) bodies.last().append(rest).append('\n')
                    prevLine = t0
                    prevConsumed = true
                    continue
                }
            }
            // Rodapé/metadados (© …, S-34-T …) nunca viram conteúdo.
            if (FOOTER_RE.containsMatchIn(t0)) {
                prevLine = t0
                prevConsumed = true
                continue
            }
            // corpo integral: tudo entre um tópico e outro é preservado
            toBody(line)
            prevLine = t0
            prevConsumed = false
        }
        // Título = número (se houver) + tema (primeira linha não-NOTA/ruído).
        // Formato: "N.º 35 — Tema completo" ou só "Tema completo" (S-31-T).
        // Hotfix 3.2.3d: antes, NOISE_RE descartava a linha do tema (começa
        // com "N.º") e sobrava a NOTA como título.
        val themeLine = lines.firstOrNull { l ->
            val t = l.text.trim()
            t.length > 10 &&
                !SPEAKER_NOTE_RE.containsMatchIn(t) &&
                !TITLE_NOISE_RE.containsMatchIn(t) &&
                MIN_RE.find(t) == null &&
                TOTAL_RE.find(t) == null
        }?.text?.trim() ?: fileName.substringBeforeLast('.')

        val numberMatch = OUTLINE_NUMBER_RE.find(themeLine)
        val number = numberMatch?.groupValues?.get(1)
        val theme = if (numberMatch != null) {
            themeLine.substring(numberMatch.range.last + 1)
                .trimStart('\t', ' ', '—', '-', ':')
                .trim()
        } else {
            themeLine
        }

        // Monta ANTES do take(140) — tema longo não perde o número.
        val composed = if (number != null && theme.isNotBlank()) {
            "N.º $number — $theme"
        } else {
            theme
        }
        title = composed.take(140)
        // Orientações do orador: NOTA inicial (sem a linha do tema) + as
        // orientações finais (linhas `[...]` no fim do último tópico). Nunca
        // vira INTRO — é material de apoio, não discurso.
        val preambleLines = preamble.toString().trim().lines().toMutableList()
        if (preambleLines.isNotEmpty() && preambleLines.first().trim() == themeLine) {
            preambleLines.removeAt(0)
        }
        val preambleText = preambleLines.joinToString("\n").trim()
        val assembled = sections.mapIndexed { i, s ->
            // trimEnd: preserva o recuo da primeira linha do corpo
            s.copy(order = i, body = bodies.getOrNull(i)?.toString()?.trimEnd().orEmpty())
        }
        val (final, trailing) = extractTrailingSpeakerNotes(assembled)
        val speakerNotes = listOf(preambleText, trailing)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return ParsedOutline(title, total, final, speakerNotes)
    }

    /**
     * Extrai as orientações finais do orador: linhas só-`[...]` no fim do
     * ÚLTIMO corpo. Elas saem do tópico (deixam de ser sub-ponto) e viram
     * nota ao orador. Instruções inline (`[Leia…]` na mesma linha do ponto)
     * NÃO são afetadas — só blocos `[...]` sozinhos no fechamento.
     *
     * @return (sections com o último corpo aparado, texto das orientações)
     */
    private fun extractTrailingSpeakerNotes(
        sections: List<OutlineSection>,
    ): Pair<List<OutlineSection>, String> {
        if (sections.isEmpty()) return sections to ""
        val last = sections.last()
        val lines = last.body.lines()
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        var start = end
        while (start > 0 && BRACKET_ONLY_RE.matches(lines[start - 1].trim())) start--
        if (start >= end) return sections to ""
        val trailing = lines.subList(start, end).joinToString("\n") { raw ->
            BRACKET_INNER_RE.find(raw.trim())?.groupValues?.get(1)?.trim() ?: raw.trim()
        }
        val newBody = lines.subList(0, start).joinToString("\n").trimEnd()
        return (sections.dropLast(1) + last.copy(body = newBody)) to trailing
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

    /** Orientações do orador (preamble) do esboço persistido. */
    fun preambleOf(json: String): String = parseEnvelope(json).first

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

    /**
     * Título inicial "Título (N min)" — só vale no começo do texto colado.
     * Retorna (título?, restante). Puro/testável.
     *
     * DÉBITO (3.2.3d): split do título no fluxo de colar. Tem lógica própria
     * (depende de "(N min)" no início); NÃO passa pelo OutlineParser.parse,
     * então não usa o formato "N.º N — Tema". Alinhar é débito de UX futura.
     */
    fun splitTitle(text: String): Pair<String?, String> {
        val m = Regex("""^(.{10,140}?\(\d+\s*min[^)]*\))[\s\n]+""").find(text.trimStart())
            ?: return null to text
        return m.groupValues[1].trim() to text.trimStart().substring(m.value.length)
    }

    /** Abreviações cujo ponto NÃO quebra frase ("Jer. 37", "cap. 5"). */
    private val ABBREVS = setOf(
        "jer", "gên", "gen", "sal", "ap", "mt", "ro", "is", "jó", "jo",
        "ecl", "pro", "efe", "fil", "col", "tim", "tia", "ped", "cor",
        "heb", "tg", "jud", "cap", "pág", "pag", "parág", "parag",
        "etc", "vol", "vers", "art", "pp", "ex", "lev", "num", "deut",
        "n", "v", "sr", "sra", "sto", "sta"
    )

    private const val DOT_HOLD = "\uFFFE" // placeholder p/ ponto protegido

    private fun protectAbbrevs(s: String): String {
        var r = s
        for (a in ABBREVS) {
            r = r.replace(
                Regex("""\b($a)\.(\s+\d)""", setOf(RegexOption.IGNORE_CASE)),
                "$1$DOT_HOLD$2"
            )
        }
        // "1. Tesouros": ponto de número antes de maiúscula não quebra frase
        r = r.replace(Regex("""\b(\d+)\.(\s+[A-ZÀ-Þ])"""), "$1$DOT_HOLD$2")
        return r
    }

    /**
     * Quebra após ")" + maiúscula SÓ com conteúdo real antes (ref longa),
     * para não triturar marcadores ("a) X", "(1) Y").
     */
    private fun splitParenRef(s: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        val re = Regex("""\)\s+(?=[A-ZÀ-Þ])""")
        for (m in re.findAll(s)) {
            val before = s.substring(start, m.range.first).trimStart('(', ' ')
            if (before.length >= 8) {
                out += s.substring(start, m.range.first + 1)
                start = m.range.last + 1
            }
        }
        out += s.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun restoreDots(s: String): String = s.replace(DOT_HOLD, ".")

    /** Marcador inicial: 0 = número (1.), 1 = letra/bullet (a), -), 2 = romano (ii.) */
    fun markerLevel(line: String): Int? {
        val t = line.trimStart()
        // número exige ponto/parêntese: "1. ", "1) ", "(1) "
        if (Regex("""^\(?\d+\)?[.)]\s+""").find(t) != null) return 0
        // bullet sempre é nível 1
        if (Regex("""^[-*•]\s+""").find(t) != null) return 1
        // letra isolada com pontuação: "a) ", "a. ", "(a) "
        val lm = Regex("""^\([a-z]\)\s+|^[a-z][.)]\s+""", RegexOption.IGNORE_CASE).find(t)
        if (lm != null) {
            // "i" isolado pode ser romano; demais letras = 1
            return 1
        }
        // romano multi-char: "ii. ", "iv) ", "(iii) "
        if (Regex("""^\(?(ii+|iii|iv|vi+|vii|viii|ix|x|xi+)\)?[.)]\s+""", RegexOption.IGNORE_CASE).find(t) != null) return 2
        return null
    }

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
            // proteção ANTES de qualquer split: senão "Jer." já fraturou
            val guarded = protectAbbrevs(line)
            guarded.split(Regex("(?<=[.!?])\\s+")).forEach { s0 ->
                // quebra também após ")" + maiúscula: "(…§ 22) Ele foi…" vira tópico novo
                splitParenRef(s0).forEach { s1 ->
                    val s = restoreDots(s1.trim())
                    // tópicos curtos valem ("Oração"); só cai vazio/número/pontuação pura
                    if (s.length > 2 && s.any { c -> c.isLetter() }) segs += Seg(s, indent)
                }
            }
        }
        // nível = posição do recuo entre os distintos (robusto a 2 ou 4 espaços)
        val distinctIndents = segs.map { it.indent }.distinct().sorted()
        val flat = distinctIndents.size <= 1
        val out = mutableListOf<Candidate>()
        for (seg in segs) {
            if (PAREN_ONLY_RE.matches(seg.text) && out.isNotEmpty()) {
                val last = out.last()
                out[out.size - 1] = last.copy(text = "${last.text} ${seg.text}")
                continue
            }
            // sem recuo na origem: numeração/bullets definem o nível
            var level = distinctIndents.indexOf(seg.indent).coerceAtLeast(0)
            if (flat) level = markerLevel(seg.text) ?: 0
            // título = frase integral (refs preservadas); só limita tamanho
            val title = if (seg.text.length > 140) seg.text.take(137).trimEnd() + "…" else seg.text
            val soft = SOFT_NOISE_RES.any { it.containsMatchIn(seg.text) }
            out += Candidate(title, out.size, suggested = !soft, level = level)
        }
        return out to dropped
    }

    /**
     * Corpo de cada tópico: concatena os candidatos seguintes com nível MAIOR
     * (subtópicos, refs, conteúdo entre tópicos), até o próximo de nível
     * menor ou igual. Texto plano (tudo nível 0) continua sem corpo.
     * Não altera candidates(). Puro/testável.
     */
    fun bodies(cands: List<Candidate>, maxChars: Int = 2000): List<String> {
        return cands.mapIndexed { i, c ->
            val sb = StringBuilder()
            var j = i + 1
            while (j < cands.size && cands[j].level > c.level) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(cands[j].text)
                if (sb.length >= maxChars) break
                j++
            }
            sb.toString().take(maxChars)
        }
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

/** Resultado da sincronia esboço <- nota. Puro/testável. */
data class SyncResult(
    /** títulos atualizados (como ficaram) */
    val updated: List<String>,
    /** seções sem ## correspondente (mantidas) */
    val missing: List<String>,
    /** ## novas na nota (candidatas a tópico) */
    val added: List<String>
)

/**
 * Recalcula as seções vinculadas a partir do markdown atual da nota.
 * Casa por título (exato, senão contém); atualiza título como escrito,
 * "(N min)" e corpo até o próximo título; ignora títulos de cartões já
 * inseridos; novas ## viram candidatas (não entram sozinhas).
 */
fun syncSections(
    linked: List<OutlineSection>,
    mdText: String,
    insertedTitles: Set<String> = emptySet()
): Pair<List<OutlineSection>, SyncResult> {
    val lines = mdText.split("\n")
    // linhas de título com índice e nível
    data class H(val idx: Int, val level: Int, val title: String)
    val heads = mutableListOf<H>()
    lines.forEachIndexed { i, line ->
        val m = Regex("^(#{2,6})\\s+(.*)$").find(line.trimStart()) ?: return@forEachIndexed
        val title = m.groupValues[2].trim().take(120).ifEmpty { null } ?: return@forEachIndexed
        heads += H(i, m.groupValues[1].length, title)
    }
    val used = mutableSetOf<Int>()
    val updatedSecs = mutableListOf<OutlineSection>()
    val updatedNames = mutableListOf<String>()
    val missing = mutableListOf<String>()
    val minRe = Regex("""\((\d{1,3})\s*min\)\s*$""")
    for (s in linked) {
        val normTarget = normalizeText(s.title)
        var bestIdx = -1
        var bestScore = 0
        heads.forEachIndexed { hi, h ->
            if (hi in used) return@forEachIndexed
            val sc = headingScore(normalizeText(h.title), normTarget)
            if (sc > bestScore) {
                bestScore = sc
                bestIdx = hi
            }
        }
        if (bestIdx < 0) {
            missing += s.title
            updatedSecs += s
            continue
        }
        used += bestIdx
        val h = heads[bestIdx]
        val mins = minRe.find(h.title)?.groupValues?.get(1)?.toIntOrNull()
        val title = h.title.replace(minRe, "").trim().ifEmpty { s.title }
        // corpo: até o próximo título (pula a linha ## de cartões inseridos)
        val bodyLines = mutableListOf<String>()
        var li = h.idx + 1
        while (li < lines.size) {
            val ht = headingTitleOf(lines[li])
            if (ht != null) {
                if (normalizeText(ht) in insertedTitles) {
                    li++
                    continue
                }
                break
            }
            bodyLines += lines[li]
            li++
        }
        val body = bodyLines.joinToString("\n").trim()
        updatedSecs += s.copy(
            title = title,
            minutes = mins ?: s.minutes,
            body = body,
            level = (h.level - 2).coerceIn(0, 4)
        )
        updatedNames += title
    }
    val linkedNorm = linked.map { normalizeText(it.title) }.toSet()
    val added = heads
        .filter { normalizeText(it.title) !in insertedTitles }
        .map { it.title }
        .filter { t ->
            linkedNorm.none { l -> headingScore(normalizeText(t), l) > 0 }
        }
        .distinct()
    return updatedSecs to SyncResult(updatedNames, missing, added)
}

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

/** Esqueleto markdown do esboço.
 *
 * 3.2.5d: não usado pela UI multi-seção (skeleton removido); mantido pelos
 * testes e como helper puro (pode servir à 3.2.4c).
 */
fun skeletonMarkdown(sections: List<OutlineSection>, preamble: String = ""): String {
    val parts = mutableListOf<String>()
    if (preamble.isNotBlank()) parts += preamble
    sections.forEach {
        // nível vira profundidade do título (##, ###, …) + recuo visual;
        // ATX tolera até 3 espaços (4+ viraria bloco de código)
        val pad = " ".repeat((it.level * 2).coerceIn(0, 3))
        val marks = "#".repeat((it.level + 2).coerceAtMost(6))
        val head = pad + marks + " " + it.title + (if (it.minutes != null) " (${it.minutes} min)" else "")
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
