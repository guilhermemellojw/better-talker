package com.bettertalker.app.data.util

/**
 * #16-fix-f: canonicalização de refs para os gates (verificador + fidelidade).
 *
 * Compara FORMAS EQUIVALENTES — abreviação de livros ("1 Coríntios" → "1co"),
 * ranges ("38:31-33" → 38:31, 38:32, 38:33) e números de página — sem
 * afrouxar: a expansão só devolve o que já está nos ranges autorizados;
 * uma página/versículo fora deles continua sendo pego. Puro/testável.
 */
internal object RefCanonicalizer {

    /** Nomes de livros por extenso → abreviação canônica (#16-fix-a). */
    private val BOOK_NORMALIZE: Map<String, String> = mapOf(
        "salmo" to "sal", "salmos" to "sal",
        "daniel" to "da",
        "1corintios" to "1co", "2corintios" to "2co",
        "genesis" to "gen", "exodo" to "ex",
        "levitico" to "le", "numeros" to "num",
        "deuteronomio" to "de", "josue" to "jos",
        "juizes" to "jz", "rute" to "rt",
        "1samuel" to "1sa", "2samuel" to "2sa",
        "1reis" to "1re", "2reis" to "2re",
        "1cronicas" to "1cr", "2cronicas" to "2cr",
        "esdras" to "esd", "neemias" to "ne",
        "ester" to "est",
        "proverbios" to "pr", "eclesiastes" to "ec",
        "cantico" to "ca", "isaias" to "is",
        "jeremias" to "je", "lamentacoes" to "la",
        "ezequiel" to "ez", "oseias" to "os",
        "joel" to "jl", "amos" to "am",
        "obadias" to "ob", "jonas" to "jon",
        "miqueias" to "miq", "habacuque" to "hab",
        "sofonias" to "sof", "ageu" to "ag",
        "zacarias" to "za", "malaquias" to "mal",
        "mateus" to "mt", "marcos" to "mr",
        "lucas" to "lu", "joao" to "jo",
        "atos" to "at", "romanos" to "ro",
        "galatas" to "gal", "efesios" to "ef",
        "filipenses" to "fil", "colossenses" to "col",
        "1tessalonicenses" to "1te", "2tessalonicenses" to "2te",
        "1timoteo" to "1ti", "2timoteo" to "2ti",
        "tito" to "tit", "filemom" to "flm",
        "hebreus" to "he", "tiago" to "tg",
        "1pedro" to "1pe", "2pedro" to "2pe",
        "1joao" to "1jo", "2joao" to "2jo", "3joao" to "3jo",
        "judas" to "ju",
        "revelacao" to "re", "apocalipse" to "re",
    )

    /** Regras pré-compiladas (nome + dígito), maiores primeiro ("1joao" antes de "joao"). */
    private val BOOK_RULES: List<Pair<Regex, String>> = BOOK_NORMALIZE.entries
        .map { (full, abbrev) -> Regex(Regex.escape(full) + "(?=\\d)") to abbrev }
        .sortedByDescending { it.first.pattern.length }

    /** "Jó 38:31-33" — livro + capítulo + versículo inicial + final. */
    private val RANGE = Regex(
        """((?:[1-3]\s*)?[A-Za-zÀ-ÿ]+\.?)\s+(\d{1,3})\s*:\s*(\d{1,3})\s*-\s*(\d{1,3})\b"""
    )

    /** "Salmo 103:20, 21" — livro + capítulo + versículo + lista continuada. */
    private val LIST = Regex(
        """((?:[1-3]\s*)?[A-Za-zÀ-ÿ]+\.?)\s+(\d{1,3}):(\d{1,3})((?:\s*,\s*\d{1,3})+)"""
    )

    /** "páginas 26 a 28" / "páginas 26-28" / "pp. 26". */
    private val PAGE_RANGE = Regex(
        """(?i)\b(?:p[áa]ginas?|pp?\.?)\s*(\d{1,4})(?:\s*(?:a|à|–|-|—)\s*(\d{1,4}))?"""
    )

    /** Range numérico solto ("26-28") — só quando não há "páginas". */
    private val BARE_RANGE = Regex("""\b(\d{1,4})\s*(?:[–—]|-)\s*(\d{1,4})\b""")

    /** Teto de segurança da expansão (nunca varre um "range" absurdo). */
    private const val MAX_EXPAND = 50

    /**
     * Minúsculas + sem acentos + só letras/dígitos — a base da comparação
     * ("Tiago 2:17" e "Leia Tiago 2:17." → "tiago217").
     */
    fun strip(text: String): String = text.lowercase()
        .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a')
        .replace('é', 'e').replace('ê', 'e')
        .replace('í', 'i')
        .replace('ó', 'o').replace('ô', 'o').replace('õ', 'o')
        .replace('ú', 'u')
        .replace('ç', 'c')
        .filter { it.isLetterOrDigit() }

    /**
     * "1 Coríntios 14:33" → "1co1433" — nomes de livros reduzidos à
     * abreviação canônica (só antes de dígito, para não poluir palavras:
     * "chamamos" ≠ "cham"+"amos").
     */
    fun canonicalBook(text: String): String {
        var base = strip(text)
        for ((re, abbrev) in BOOK_RULES) base = base.replace(re, abbrev)
        return base
    }

    /**
     * "Jó 38:31-33" → ["Jó 38:31", "Jó 38:32", "Jó 38:33"] — a forma com o
     * livro preservado (o outro lado canonicaliza igual).
     */
    fun expandRanges(text: String): List<String> {
        val out = mutableListOf<String>()
        RANGE.findAll(text).forEach { m ->
            val book = m.groupValues[1].trim()
            val ch = m.groupValues[2].toIntOrNull() ?: return@forEach
            val v1 = m.groupValues[3].toIntOrNull() ?: return@forEach
            val v2 = m.groupValues[4].toIntOrNull() ?: return@forEach
            if (ch <= 0 || v1 <= 0 || v2 < v1 || v2 - v1 > MAX_EXPAND) return@forEach
            for (v in v1..v2) out += "$book $ch:$v"
        }
        return out
    }

    /**
     * "páginas 26 a 28" → ["26","27","28"]; sem o prefixo "páginas", aceita
     * o range numérico solto ("26-28"). Fora de um range, só o número.
     */
    fun extractPageNumbers(text: String): List<String> {
        val out = mutableListOf<String>()
        PAGE_RANGE.findAll(text).forEach { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@forEach
            val b = m.groupValues[2].toIntOrNull()
            if (b != null && b >= a && b - a <= MAX_EXPAND) for (n in a..b) out += n.toString()
            else out += a.toString()
        }
        if (out.isEmpty()) {
            BARE_RANGE.findAll(text).forEach { m ->
                val a = m.groupValues[1].toIntOrNull() ?: return@forEach
                val b = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (b >= a && b - a <= MAX_EXPAND) for (n in a..b) out += n.toString()
            }
        }
        return out.distinct()
    }

    /**
     * Formas canônicas de um texto para a comparação entre os dois lados:
     * o texto inteiro abreviado + cada range/lista expandido (abreviado) +
     * os números de página. O chamador compara por contenção.
     */
    fun canonicalize(text: String): Set<String> {
        val expanded = expandRanges(text) + expandLists(text)
        return (listOf(canonicalBook(text)) + expanded.map { canonicalBook(it) } + extractPageNumbers(text))
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** "Salmo 103:20, 21" → ["Salmo 103:20", "Salmo 103:21"] (lista continua o capítulo). */
    private fun expandLists(text: String): List<String> {
        val out = mutableListOf<String>()
        LIST.findAll(text).forEach { m ->
            val book = m.groupValues[1].trim()
            val ch = m.groupValues[2]
            out += "$book $ch:${m.groupValues[3]}"
            Regex("""\d{1,3}""").findAll(m.groupValues[4]).forEach { v ->
                out += "$book $ch:${v.value}"
            }
        }
        return out
    }
}
