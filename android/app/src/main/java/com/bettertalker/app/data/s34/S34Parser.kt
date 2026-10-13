package com.bettertalker.app.data.s34

import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.S34Detector
import com.bettertalker.app.data.util.normalizeText

/**
 * Fase 19-B.2 — parser determinístico S-34 → S34Document.
 *
 * Responde "o que está no documento e como está organizado" (nunca
 * "o que é relevante" — isso é retrieval, fase futura). Puro: sem Room,
 * sem Context, sem IO, sem rede. Mesma entrada => mesma saída.
 *
 * Regras explícitas (ver docs/F19_B2_S34_PARSER.md):
 * - ordem = encontro no documento (índice 1-based), nunca score;
 * - objetivo SÓ com rótulo "Objetivo:" (função estrutural verificada);
 * - título = linha "Tema:" ou primeira linha não-marcador (fallback cru);
 * - ponto = linha numerada (`1.`/`2)`); subponto = `a)`/`b)` — sempre filho
 *   da seção aberta (nunca do ponto seguinte; órfão vira headerLines);
 * - refs extraídas por linha via RefDetector (Bíblia) + RefDetector.detect
 *   (publicações), anexadas à seção/subseção da linha, em ordem global;
 * - ambíguo => preservado como texto (headerLines/corpo), nunca inventado;
 * - SEMPRE sem introduction/conclusion (camada futura separada).
 */
object S34Parser {

    private val THEME_RE = Regex("""(?i)^\s*tema\s*:\s*(.+)$""")
    private val POINT_RE = Regex("""^\s*\d{1,2}[.)]\s*(\S.*)$""")
    private val SUBPOINT_RE = Regex("""^\s*[a-z]\)\s*(.+)$""")
    private val TRAILING_MIN_RE = Regex("""\s*[\[(]\s*\d+\s*(min\.?|minutos?)\s*[\])]\s*$""", RegexOption.IGNORE_CASE)

    /**
     * T1 (Bug #12) — seção temporizada SEM numeração: linha inteira termina em
     * `(N min)` e o restante é o título (formato real do S-34: título case,
     * às vezes entre aspas/negrito). Espelha a heurística do OutlineParser.
     */
    private val SECTION_MIN_RE = Regex(
        """^\s*(.+?)\s*[\[(]\s*(\d+)\s*(min\.?|minutos?)\s*[\])]\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Limpa aspas/negrito do título de seção ("“X” (3 min)" → "X"). */
    private fun cleanSectionTitle(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("“", "”")
            .removeSurrounding("**").trim()

    /**
     * T1 (Bug #12) — espaços especiais vindos da extração real (NBSP etc.):
     * RTF/DOCX trazem "(3\u00A0min)" e o `\s` do Java NÃO cobre U+00A0.
     * Normalizar aqui vale para todos os regexes do parser (determinístico).
     */
    private val SPECIAL_SPACE_RE = Regex("[\u00A0\u2007\u202F]")

    /** F5a: limpa marcadores markdown da linha da NOTA ("> citação", "*ênfase*"). */
    private fun cleanSpeakerNoteLine(raw: String): String =
        raw.trim().removePrefix(">").trim().trim('*').trim()

    private data class BodyLine(val lineNo: Int, val text: String)
    private data class MutableSub(val content: String, val sourceLine: Int)
    private data class MutableSection(
        val order: Int,
        var title: String,
        var minutes: Int?,
        val sourceLine: Int,
        val body: MutableList<BodyLine> = mutableListOf(),
        val subs: MutableList<MutableSub> = mutableListOf()
    )

    fun parseS34(rawText: String): S34Document {
        val lines = rawText.replace("\uFEFF", "").replace(SPECIAL_SPACE_RE, " ").split("\n")
        var refOrder = 0

        fun scanRefs(text: String, lineNo: Int): List<S34Reference> {
            val out = mutableListOf<S34Reference>()
            for (b in RefDetector.detectBible(text)) {
                out += S34Reference(
                    type = S34RefType.BIBLE,
                    rawText = text.trim(),
                    normalizedReference = "${b.label}|${b.chapter}|${b.verse}",
                    order = ++refOrder,
                    sourceLine = lineNo,
                    bible = b
                )
            }
            for (p in RefDetector.detect(text)) {
                out += S34Reference(
                    type = S34RefType.PUBLICATION,
                    rawText = p.raw,
                    normalizedReference = normalizeText(p.raw),
                    order = ++refOrder,
                    sourceLine = lineNo,
                    publication = p
                )
            }
            return out
        }

        var title = ""
        var objective: String? = null
        val headerLines = mutableListOf<String>()
        val sections = mutableListOf<MutableSection>()
        var inObjective = false
        val objectiveLines = mutableListOf<String>()
        var current: MutableSection? = null

        fun closeObjective() {
            if (inObjective) {
                inObjective = false
                val text = objectiveLines.joinToString("\n").trim()
                if (text.isNotEmpty()) objective = text
                objectiveLines.clear()
            }
        }

        // F5a — bloco NOTA do orador (persona). Captura até cabeçalho de
        // seção/subponto, próximo "Objetivo:"/"NOTA:" ou linha em branco
        // DUPLA. Uma branca simples separa parágrafos ("\n\n").
        var speakerNote: String? = null
        var inSpeakerNote = false
        var speakerNoteBlanks = 0
        val speakerNoteLines = mutableListOf<String>()

        fun flushSpeakerNote() {
            if (!inSpeakerNote) return
            inSpeakerNote = false
            speakerNoteBlanks = 0
            if (speakerNoteLines.isEmpty()) return
            val paragraphs = mutableListOf<StringBuilder>()
            for (l in speakerNoteLines) {
                if (l.isBlank()) {
                    if (paragraphs.isNotEmpty() && paragraphs.last().isNotEmpty()) {
                        paragraphs += StringBuilder()
                    }
                    continue
                }
                if (paragraphs.isEmpty()) paragraphs += StringBuilder()
                if (paragraphs.last().isNotEmpty()) paragraphs.last().append(' ')
                paragraphs.last().append(l)
            }
            speakerNoteLines.clear()
            val block = paragraphs.filter { it.isNotEmpty() }
                .joinToString("\n\n") { it.toString() }
                .trim()
            if (block.isEmpty()) return
            speakerNote = listOfNotNull(speakerNote, block).joinToString("\n\n")
        }

        lines.forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            val line = raw.trim()
            if (line.isEmpty()) {
                // Parágrafo quebrou: objetivo de múltiplos parágrafos é
                // truncado de propósito (conservador — §15; documentado).
                closeObjective()
                if (inSpeakerNote) {
                    speakerNoteBlanks++
                    if (speakerNoteBlanks >= 2) flushSpeakerNote()
                    else speakerNoteLines += "" // marca quebra de parágrafo
                }
                return@forEachIndexed
            }
            // F5a: termina a captura da NOTA ao encontrar o próximo bloco
            // estrutural (seção/subponto/cabeçalhos) — nunca vaza para o corpo.
            if (inSpeakerNote) {
                val terminator = POINT_RE.matches(line) ||
                    SECTION_MIN_RE.matchEntire(line) != null ||
                    SUBPOINT_RE.matches(line) ||
                    S34Detector.OBJECTIVE_RE.containsMatchIn(line) ||
                    S34Detector.SPEAKER_NOTE_RE.containsMatchIn(line)
                if (terminator) flushSpeakerNote()
            }
            if (POINT_RE.matches(line)) {
                closeObjective()
                val order = sections.size + 1
                var rest = POINT_RE.matchEntire(line)!!.groupValues[1].trim()
                val minutes = if (TRAILING_MIN_RE.containsMatchIn(rest)) {
                    OutlineParser.MIN_RE.find(rest)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        .also { rest = TRAILING_MIN_RE.replace(rest, "").trim() }
                } else null
                val sec = MutableSection(
                    order = order,
                    title = rest.ifBlank { "Ponto $order" },
                    minutes = minutes,
                    sourceLine = lineNo
                )
                sections += sec
                current = sec
                return@forEachIndexed
            }
            // T1 (Bug #12): seção temporizada sem numeração (formato real).
            // Prioridade: ponto numerado (acima); subponto (a)/b)) nunca vira seção.
            if (!SUBPOINT_RE.matches(line)) {
                SECTION_MIN_RE.matchEntire(line)?.let { m ->
                    val t = cleanSectionTitle(m.groupValues[1])
                    if (t.isNotBlank()) {
                        closeObjective()
                        val order = sections.size + 1
                        val sec = MutableSection(
                            order = order,
                            title = t,
                            minutes = m.groupValues[2].toIntOrNull(),
                            sourceLine = lineNo
                        )
                        sections += sec
                        current = sec
                        return@forEachIndexed
                    }
                }
            }
            if (inObjective) {
                objectiveLines += line
                return@forEachIndexed
            }
            val sub = SUBPOINT_RE.matchEntire(line)
            if (sub != null) {
                val target = current
                if (target != null) {
                    target.subs += MutableSub(line, lineNo)
                } else {
                    // Órfão antes de qualquer ponto: preservado, sem dono inventado.
                    headerLines += line
                }
                return@forEachIndexed
            }
            THEME_RE.matchEntire(line)?.let {
                if (title.isBlank()) title = it.groupValues[1].trim()
                return@forEachIndexed
            }
            if (S34Detector.OBJECTIVE_RE.containsMatchIn(line)) {
                val rest = line.substringAfter(":").trim()
                inObjective = true
                if (rest.isNotEmpty()) objectiveLines += rest
                return@forEachIndexed
            }
            // F5a: inicio do bloco NOTA do orador (persona). O corpo é
            // capturado linha a linha até um terminador/bloco.
            S34Detector.SPEAKER_NOTE_RE.find(line)?.let { m ->
                flushSpeakerNote() // fecha a anterior (concatena com \n\n)
                inSpeakerNote = true
                speakerNoteBlanks = 0
                val rest = line.substring(m.range.last + 1).trim()
                if (rest.isNotEmpty()) speakerNoteLines += cleanSpeakerNoteLine(rest)
                return@forEachIndexed
            }
            if (inSpeakerNote) {
                speakerNoteBlanks = 0
                val clean = cleanSpeakerNoteLine(line)
                if (clean.isNotEmpty()) speakerNoteLines += clean
                return@forEachIndexed
            }
            val sec = current
            if (sec != null) {
                sec.body += BodyLine(lineNo, line)
            } else {
                if (title.isBlank() && !S34Detector.MARKER_RE.containsMatchIn(line)) {
                    title = line
                } else {
                    headerLines += line
                }
            }
        }
        closeObjective()
        flushSpeakerNote()

        val docSections = sections.map { s ->
            val subsections = s.subs.mapIndexed { si, sub ->
                S34Subsection(
                    id = "sec-${s.order}-${si + 1}",
                    order = si + 1,
                    content = sub.content,
                    references = scanRefs(sub.content, sub.sourceLine),
                    sourceLine = sub.sourceLine
                )
            }
            S34Section(
                id = "sec-${s.order}",
                order = s.order,
                title = s.title,
                minutes = s.minutes,
                content = s.body.joinToString("\n") { it.text },
                subsections = subsections,
                references = s.body.flatMap { bl -> scanRefs(bl.text, bl.lineNo) },
                sourceLine = s.sourceLine
            )
        }
        return S34Document(
            id = "s34-" + hashText(rawText),
            title = title,
            objective = objective,
            speakerNote = speakerNote?.takeIf { it.isNotBlank() },
            sections = docSections,
            headerLines = headerLines
        )
    }
}
