package com.bettertalker.app.data.util

/**
 * C2.5 — normalização de referências de revista (Sentinela/Despertai).
 *
 * Puro: recebe o texto da referência e devolve a edição canônica, o tipo
 * (estudo × público), a data/issue, página/faixa, idioma e a confiança.
 * Ambiguidades conhecidas são marcadas, nunca escolhidas em silêncio.
 *
 * A chave `editionKey` segue o formato já usado pelo resolvedor
 * (`w|2025|7`, `wp|2016|1`, `w|2014|5|15`, `g|2013|8`, `gn|2017|3`);
 * `displayKey` é a forma compacta da tabela C2.5 (`g|2017 · issue 3`).
 * O idioma é um campo separado — nunca entra na chave.
 */
enum class MagazineFamily { WATCHTOWER, AWAKE }

enum class MagazineKind { STUDY, PUBLIC, UNKNOWN }

enum class MagazineLanguage { PT, EN }

enum class MagazineConfidence { EXACT, AMBIGUOUS }

data class MagazineEdition(
    val raw: String,
    val family: MagazineFamily,
    val kind: MagazineKind,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
    /** Edição numerada (Despertai! N.º 3/2017, g16.1). */
    val issue: Int? = null,
    /** w73 652 — volume encadernado, página avulsa. */
    val boundVolume: Boolean = false,
    val page: Int? = null,
    val pageEnd: Int? = null,
    val language: MagazineLanguage = MagazineLanguage.PT,
    val confidence: MagazineConfidence = MagazineConfidence.EXACT,
    val note: String? = null,
) {
    val isIssue: Boolean get() = issue != null
    val isAmbiguous: Boolean get() = confidence == MagazineConfidence.AMBIGUOUS

    /**
     * Chave de edição compatível com o resolvedor atual.
     * `gn` é usado para issue numerada; `w`/`wp`/`g` para mês/dia.
     */
    fun editionKey(): String = baseKey()

    private fun baseKey(): String = when {
        boundVolume -> "w|$year|bound"
        family == MagazineFamily.AWAKE && isIssue -> "gn|$year|$issue"
        family == MagazineFamily.AWAKE && day != null -> "g|$year|$month|$day"
        family == MagazineFamily.AWAKE -> "g|$year|$month"
        kind == MagazineKind.PUBLIC && day == null -> "wp|$year|$month"
        day != null -> "w|$year|$month|$day"
        else -> "w|$year|$month"
    }

    /** Forma compacta para UI/testes: `g|2017 · issue 3`, `w|2014|5|15`. */
    fun displayKey(): String = when {
        boundVolume -> "w|$year|bound"
        family == MagazineFamily.AWAKE && isIssue -> "g|$year · issue $issue"
        day != null -> "${if (family == MagazineFamily.AWAKE) "g" else "w"}|$year|$month|$day"
        else -> baseKey()
    }

    fun pageLabel(): String? {
        val p = page ?: return null
        val end = pageEnd
        return if (end != null && end != p) "$p-$end" else "$p"
    }
}

object MagazineEditionParser {

    private val MONTHS = listOf(
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )

    private val WS = Regex("\\s+")
    private val EN_RE = Regex("\\b(?:em\\s+ingles|ingles|english)\\b", RegexOption.IGNORE_CASE)

    /** "N.º" / "n.°" / "número" — prefixo opcional do número da edição. */
    private const val ISSUE_PREFIX = """(?:n[úu]mero|n\.?\s*[º°o]?)"""

    /**
     * Retorna null quando não há edição de revista reconhecível. Ambiguidade
     * não é erro: devolve [MagazineConfidence.AMBIGUOUS] com nota.
     */
    fun parse(raw: String): MagazineEdition? {
        val original = raw.trim()
        if (original.isBlank()) return null
        val normalized = original.replace(WS, " ").trim()
        val text = normalizeAccentsForMatch(normalized)

        // 1) Sentinela explícita (público/estudo, data, issue, mês por extenso).
        parseSentinela(text, original)?.let { return it }

        // 2) Códigos w / wp (moderno e antigo dia/mês) e volume encadernado.
        parseWatchtowerCode(text, original)?.let { return it }

        // 3) Despertai!/g (mês/ano, dia/mês, issue, mês por extenso).
        parseAwake(text, original)?.let { return it }

        return null
    }

    private fun parseSentinela(
        text: String,
        raw: String,
    ): MagazineEdition? {
        // "Sentinela (público) N.° 1 de 2016" / "(estudo)".
        Regex(
            """(?:a\s+)?sentinela\s*\((publico|estudo)\)\s*,?\s*$ISSUE_PREFIX?\s*""" +
                """(\d{1,2})\s+de\s+(\d{4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val explicit = m.groupValues[1].lowercase()
            val num = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            if (num !in 1..12) return null
            val kind = if (explicit == "publico") MagazineKind.PUBLIC else MagazineKind.STUDY
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.WATCHTOWER, kind, year, month = num,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Sentinela (público/estudo) 05/16".
        Regex(
            """(?:a\s+)?sentinela\s*\((publico|estudo)\)\s*,?\s*(\d{1,2})/(\d{2,4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val explicit = m.groupValues[1].lowercase()
            val month = m.groupValues[2].toInt()
            val year = fullYear(m.groupValues[3])
            if (month !in 1..12) return null
            val kind = if (explicit == "publico") MagazineKind.PUBLIC else MagazineKind.STUDY
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.WATCHTOWER, kind, year, month = month,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Sentinela número 3 de 2019" / "A Sentinela, n.º 2 de 2017" — sem
        // explicit, é a pública (wp), como no detector atual.
        Regex(
            """(?:a\s+)?sentinela\s*,?\s+$ISSUE_PREFIX?\s*(\d{1,2})\s+de\s+(\d{4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val num = m.groupValues[1].toInt()
            if (num !in 1..12) return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.WATCHTOWER, MagazineKind.PUBLIC,
                m.groupValues[2].toInt(), month = num,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Sentinela de maio de 2011" / "A Sentinela, de março de 2019".
        Regex(
            """(?:a\s+)?sentinela\s*,?\s+de\s+([a-z]+)\s+de\s+(\d{4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val month = monthOf(m.groupValues[1]) ?: return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.WATCHTOWER, MagazineKind.STUDY,
                m.groupValues[2].toInt(), month,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Sentinela 15/05/14" (DD/MM/AA).
        Regex(
            """(?:a\s+)?sentinela\s*,?\s+(\d{1,2})/(\d{1,2})/(\d{2,4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = fullYear(m.groupValues[3])
            if (month !in 1..12 || day !in 1..31) return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.WATCHTOWER, kindForDay(day), year,
                month = month, day = day,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Sentinela 07/21" / "01/20" — MM/AA, estudo mensal.
        Regex(
            """(?:a\s+)?sentinela\s*,?\s+(\d{1,2})/(\d{2,4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val month = m.groupValues[1].toInt()
            if (month !in 1..12) return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.WATCHTOWER, MagazineKind.STUDY,
                fullYear(m.groupValues[2]), month = month,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        return null
    }

    private fun parseWatchtowerCode(
        text: String,
        raw: String,
    ): MagazineEdition? {
        // wp20.1 / wp25.07
        Regex("""\bwp\s*(\d{2})[./](\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val year = fullYear(m.groupValues[1])
                val month = m.groupValues[2].toInt()
                if (month !in 1..12) return null
                val pageRange = pageAfter(text, m.range.last)
                return edition(
                    raw, MagazineFamily.WATCHTOWER, MagazineKind.PUBLIC, year, month,
                    page = pageRange?.first, pageEnd = pageRange?.second,
                )
            }

        // wp24 N.º 1 / wp24 numero 1.
        Regex(
            """\bwp\s*(\d{2})\s*$ISSUE_PREFIX\s*(\d{1,2})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val year = fullYear(m.groupValues[1])
            val num = m.groupValues[2].toInt()
            if (num !in 1..12) return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.WATCHTOWER, MagazineKind.PUBLIC, year, month = num,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // w25.07 / w 24/12
        Regex("""\bw\s*(\d{2})[./](\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val year = fullYear(m.groupValues[1])
                val month = m.groupValues[2].toInt()
                if (month !in 1..12) return null
                val pageRange = pageAfter(text, m.range.last)
                return edition(
                    raw, MagazineFamily.WATCHTOWER, MagazineKind.STUDY, year, month,
                    page = pageRange?.first, pageEnd = pageRange?.second,
                )
            }

        // w14 15/5 — antigo dia/mês.
        Regex("""\bw\s*(\d{2})\s+(\d{1,2})[/.](\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val year = fullYear(m.groupValues[1])
                val day = m.groupValues[2].toInt()
                val month = m.groupValues[3].toInt()
                if (month !in 1..12 || day !in 1..31) return null
                val pageRange = pageAfter(text, m.range.last)
                return edition(
                    raw, MagazineFamily.WATCHTOWER, kindForDay(day), year,
                    month = month, day = day,
                    page = pageRange?.first, pageEnd = pageRange?.second,
                )
            }

        // w73 652 — volume encadernado; o número de 3 dígitos é a página.
        Regex("""\bw\s*(\d{2})\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                return edition(
                    raw, MagazineFamily.WATCHTOWER, MagazineKind.UNKNOWN,
                    fullYear(m.groupValues[1]), page = m.groupValues[2].toInt(),
                    boundVolume = true,
                )
            }

        return null
    }

    private fun parseAwake(
        text: String,
        raw: String,
    ): MagazineEdition? {
        // "Despertai! N.º 3 de 2017" / "Despertai! N.º 1 2024".
        Regex(
            """despertai!?,?\s*$ISSUE_PREFIX?\s*(\d{1,2})\s+(?:de\s+)?(\d{4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val issue = m.groupValues[1].toInt()
            if (issue !in 1..12) return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.AWAKE, MagazineKind.PUBLIC,
                m.groupValues[2].toInt(), issue = issue,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Despertai! de maio de 2011".
        Regex(
            """despertai!?,?\s+de\s+([a-z]+)\s+de\s+(\d{4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val month = monthOf(m.groupValues[1]) ?: return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.AWAKE, MagazineKind.PUBLIC, m.groupValues[2].toInt(), month,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Despertai! 08/07/02" — dia/mês/ano.
        Regex(
            """despertai!?,?\s*(\d{1,2})/(\d{1,2})/(\d{2,4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = fullYear(m.groupValues[3])
            if (month !in 1..12 || day !in 1..31) return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.AWAKE, MagazineKind.PUBLIC, year,
                month = month, day = day,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // "Despertai! 08/13" / "Despertai!, 8/2013" — mês/ano.
        Regex(
            """despertai!?,?\s*(\d{1,2})/(\d{2,4})\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { m ->
            val month = m.groupValues[1].toInt()
            if (month !in 1..12) return null
            val pageRange = pageAfter(text, m.range.last)
            return edition(
                raw, MagazineFamily.AWAKE, MagazineKind.PUBLIC,
                fullYear(m.groupValues[2]), month = month,
                page = pageRange?.first, pageEnd = pageRange?.second,
            )
        }

        // g16.1 / g18.2 / g19.03 — issue (g moderno numerado).
        Regex("""\bg\s*(\d{2})[.](\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val issue = m.groupValues[2].toInt()
                if (issue !in 1..12) return null
                val pageRange = pageAfter(text, m.range.last)
                return edition(
                    raw, MagazineFamily.AWAKE, MagazineKind.PUBLIC,
                    fullYear(m.groupValues[1]), issue = issue,
                    page = pageRange?.first, pageEnd = pageRange?.second,
                )
            }

        // g 6/14 / g 10/12 / g 1/24 — mês/ano. g 1/16 é a ambiguidade conhecida.
        Regex("""\bg\s*(\d{1,2})/(\d{2,4})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val month = m.groupValues[1].toInt()
                if (month !in 1..12) return null
                val year = fullYear(m.groupValues[2])
                val compact = m.value.filterNot { it.isWhitespace() }
                val ambiguous = compact.equals("g1/16", ignoreCase = true)
                val pageRange = pageAfter(text, m.range.last)
                return edition(
                    raw, MagazineFamily.AWAKE, MagazineKind.PUBLIC, year, month = month,
                    page = pageRange?.first, pageEnd = pageRange?.second,
                    confidence = if (ambiguous) MagazineConfidence.AMBIGUOUS else MagazineConfidence.EXACT,
                    note = if (ambiguous) {
                        "g 1/16 pode ser janeiro/2016 ou edição 1/2016"
                    } else null,
                )
            }

        // g92 8/10 / g93 22/1 — antigo dia/mês.
        Regex("""\bg\s*(\d{2})\s+(\d{1,2})[/.](\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val year = fullYear(m.groupValues[1])
                val day = m.groupValues[2].toInt()
                val month = m.groupValues[3].toInt()
                if (month !in 1..12 || day !in 1..31) return null
                val pageRange = pageAfter(text, m.range.last)
                return edition(
                    raw, MagazineFamily.AWAKE, MagazineKind.PUBLIC, year,
                    month = month, day = day,
                    page = pageRange?.first, pageEnd = pageRange?.second,
                )
            }

        return null
    }

    private fun edition(
        raw: String,
        family: MagazineFamily,
        kind: MagazineKind,
        year: Int,
        month: Int? = null,
        day: Int? = null,
        issue: Int? = null,
        page: Int? = null,
        boundVolume: Boolean = false,
        pageEnd: Int? = null,
        confidence: MagazineConfidence = MagazineConfidence.EXACT,
        note: String? = null,
    ): MagazineEdition = MagazineEdition(
        raw = raw,
        family = family,
        kind = kind,
        year = year,
        month = month,
        day = day,
        issue = issue,
        boundVolume = boundVolume,
        page = page,
        pageEnd = pageEnd,
        language = if (EN_RE.containsMatchIn(normalizeAccentsForMatch(raw))) {
            MagazineLanguage.EN
        } else {
            MagazineLanguage.PT
        },
        confidence = confidence,
        note = note,
    )

    /**
     * Número logo após a edição; ranges abreviados são expandidos.
     * [editionEnd] é o índice inclusivo do fim do match (MatchResult.range.last).
     */
    private fun pageAfter(text: String, editionEnd: Int): Pair<Int, Int?>? {
        val tail = text.substring((editionEnd + 1).coerceAtMost(text.length))
        val m = Regex(
            """^\s*[,;]?\s*(?:(?:pag(?:ina)?s?|pg|p)\.?\s*)?(\d{1,4})(?:\s*[-–]\s*(\d{1,4}))?""",
            RegexOption.IGNORE_CASE,
        ).find(tail) ?: return null
        val start = m.groupValues[1].toIntOrNull() ?: return null
        val endRaw = m.groupValues[2].toIntOrNull()
        return start to endRaw?.let { expandRange(start, it) }
    }

    /** "26-9" → 29, "140-1" → 141, "22-4" → 24. */
    private fun expandRange(start: Int, end: Int): Int {
        if (end >= start) return end
        var scale = 1
        var n = end
        while (n > 0) {
            scale *= 10
            n /= 10
        }
        return (start / scale) * scale + end
    }

    private fun kindForDay(day: Int): MagazineKind = when (day) {
        1 -> MagazineKind.PUBLIC
        15 -> MagazineKind.STUDY
        else -> MagazineKind.UNKNOWN
    }

    /**
     * Pivô de século: 00–29 → 2000s; 30–99 → 1900s. As revistas antigas
     * (w45, g45, w73…) são do século passado; as modernas usam 2 dígitos
     * apenas a partir de 2000.
     */
    private fun fullYear(raw: String): Int {
        val v = raw.toInt()
        return if (v < 100) {
            if (v <= 29) 2000 + v else 1900 + v
        } else v
    }

    private fun monthOf(name: String): Int? {
        val n = normalizeAccentsForMatch(name).lowercase()
        return MONTHS.indexOf(n).takeIf { it >= 0 }?.plus(1)
    }

    private fun normalizeAccentsForMatch(s: String): String = s
        .lowercase()
        .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a')
        .replace('é', 'e').replace('ê', 'e')
        .replace('í', 'i')
        .replace('ó', 'o').replace('ô', 'o').replace('õ', 'o')
        .replace('ú', 'u')
        .replace('ç', 'c')
}
