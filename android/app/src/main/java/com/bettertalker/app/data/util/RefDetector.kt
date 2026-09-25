package com.bettertalker.app.data.util

import com.bettertalker.app.data.db.AttachmentEntity
import java.net.URLEncoder

/**
 * Detecta referências a publicações no texto da nota (português natural)
 * e verifica se a EDIÇÃO EXATA está baixada localmente.
 * Textos bíblicos (Gên 1:26) são ignorados por decisão de escopo.
 */
object RefDetector {

    enum class Kind { MAGAZINE, BOOK }

    data class DetectedRef(
        val raw: String,
        val kind: Kind,
        /** g | gn | w | wp | be | th | nome-normalizado */
        val pubKey: String,
        /**
         * chave de edição exata:
         * "g|2013|8" (mensal antiga), "gn|2024|1" (numerada nova),
         * "w|2024|12" (estudo mensal), "w|1999|5|1" (antiga dia/mês),
         * "wp|2019|3" (pública numerada), "book|be"
         */
        val editionKey: String,
        val label: String
    )

    data class RefStatus(
        val ref: DetectedRef,
        val resolved: Boolean,
        val fileName: String? = null,
        val downloadUrl: String = "",
        val hint: String = "",
        /** true = página da edição; false = página de busca/revistas */
        val exact: Boolean = true,
        /** arquivo direto via API oficial (download em 1 toque); null = só página */
        val apiPub: String? = null,
        val apiIssue: String? = null,
        /** numerada sem código derivável: sonda meses via API no toque */
        val probePub: String? = null,
        val probeYear: Int? = null
    )

    // Despertai! 08/13 | Despertai!, 8/2013 (mensal antiga)
    private val AWAKE_RE = Regex(
        """Despertai!?,?\s*(\d{1,2})/(\d{2,4})""",
        RegexOption.IGNORE_CASE
    )
    // Despertai! N.º 1 2024 (numerada nova)
    private val AWAKE_NUM_RE = Regex(
        """Despertai!?,?\s*N\.?(?:º|o|°)?\s*(\d{1,2})\s*(?:de\s*)?(\d{4})""",
        RegexOption.IGNORE_CASE
    )
    // Sentinela número 3 de 2019 | A Sentinela, n.º 2 de 2017 (pública numerada)
    private val WATCHTOWER_RE = Regex(
        """(?:A\s+)?Sentinela,?\s+(?:n\.?(?:º|o|°)?\s*)?(?:n[úu]mero\s+)?(\d{1,2})\s+de\s+(\d{4})""",
        RegexOption.IGNORE_CASE
    )
    // Sentinela de março de 2019 (estudo mensal por extenso)
    private val WATCH_MONTH_RE = Regex(
        """(?:A\s+)?Sentinela\s+de\s+(janeiro|fevereiro|mar[cç]o|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\s+de\s+(\d{4})""",
        RegexOption.IGNORE_CASE
    )
    // w24.12 | w 24/12 (estudo mensal moderna)
    private val W_CODE_RE = Regex("""\bw\s*(\d{2})[./](\d{1,2})\b""")
    // w99 1/5 | w99 15/5 (antiga dia/mês: dia 1º = pública, 15 = estudo)
    private val W_DAY_RE = Regex("""\bw\s*(\d{2})\s+(\d{1,2})/(\d{1,2})\b""")
    // wp24 N.º 1 | wp19.3 (pública por código)
    private val WP_CODE_RE = Regex("""\bwp\s*(\d{2})(?:[./](\d{1,2})|\s*(?:N\.?(?:º|o|°)?|n[úu]mero)\s*(\d{1,2}))?""")
    // g 1/24 | g1/24
    private val G_CODE_RE = Regex("""\bg\s*(\d{1,2})/(\d{2,4})\b""")
    // citação por sigla do catálogo: lff cap. 5 | be pág. 52 | th lição 3
    // (sigla validada contra PubCatalog; "na" excluído por colidir com preposição)
    private val SYMBOL_REF_RE = Regex(
        """\b([A-Za-z]{2,4}(?:-[12])?)\s+(cap\.?|capítulo|li[cç][aã]o|p[áa]g\.?|página|par[áa]g\.?|estudo|n\.?(?:º|o|°)?)""",
        RegexOption.IGNORE_CASE
    )

    private val PT_MONTHS = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    )

    /** Slug de mês para URL (site usa sem acento: marco). */
    private fun monthSlug(m: Int): String = when (m) {
        3 -> "marco"
        else -> PT_MONTHS[m - 1]
    }

    /** Livros/brochuras por nome (normalizado) -> chave. */
    private val BOOK_NAMES = listOf(
        "beneficie se da escola do ministerio teocratico" to "be",
        "beneficie se" to "be",
        "escola do ministerio teocratico" to "be",
        "melhore sua leitura e seu ensino" to "th",
        "melhore a sua leitura e o seu ensino" to "th",
        "leitura e ensino" to "th",
        "entenda a biblia" to "bhs"
    )

    const val MAGAZINES_LANDING = "https://www.jw.org/pt/biblioteca/revistas/"
    private const val JW_SEARCH = "https://www.jw.org/pt/busca/?q="

    private fun fullYear(yy: Int): Int = if (yy >= 50) 1900 + yy else 2000 + yy

    fun detect(text: String): List<DetectedRef> {
        val out = mutableListOf<DetectedRef>()
        val seen = mutableSetOf<String>()

        fun add(ref: DetectedRef) {
            // mesma edição citada 2x (págs. diferentes) = 1 entrada
            if (seen.add(ref.editionKey)) out += ref
        }

        AWAKE_RE.findAll(text).forEach { m ->
            val month = m.groupValues[1].toInt()
            var year = m.groupValues[2].toInt()
            if (year < 100) year = fullYear(year)
            add(
                DetectedRef(
                    m.value.trim(), Kind.MAGAZINE, "g",
                    "g|$year|$month", "Despertai! $month/$year"
                )
            )
        }
        AWAKE_NUM_RE.findAll(text).forEach { m ->
            val num = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            add(
                DetectedRef(
                    m.value.trim(), Kind.MAGAZINE, "gn",
                    "gn|$year|$num", "Despertai! N.º $num $year"
                )
            )
        }
        WATCHTOWER_RE.findAll(text).forEach { m ->
            val num = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            add(
                DetectedRef(
                    m.value.trim(), Kind.MAGAZINE, "wp",
                    "wp|$year|$num", "A Sentinela N.º $num $year (pública)"
                )
            )
        }
        WATCH_MONTH_RE.findAll(text).forEach { m ->
            val monthName = m.groupValues[1].lowercase().replace("marco", "março")
            val month = PT_MONTHS.indexOf(monthName) + 1
            val year = m.groupValues[2].toInt()
            if (month > 0) {
                add(
                    DetectedRef(
                        m.value.trim(), Kind.MAGAZINE, "w",
                        "w|$year|$month", "A Sentinela (estudo), $monthName de $year"
                    )
                )
            }
        }
        W_DAY_RE.findAll(text).forEach { m ->
            // formato antigo dia/mês: w99 1/5 (pública, dia 1º), w99 15/5 (estudo, dia 15)
            val year = fullYear(m.groupValues[1].toInt())
            val day = m.groupValues[2].toInt()
            val month = m.groupValues[3].toInt()
            if (month in 1..12 && day in 1..31) {
                val edition = if (day == 1) "pública" else "estudo"
                val monthName = PT_MONTHS[month - 1]
                // pág./§ logo após vão só para o rótulo (ex: "w08 15/10 11 § 18")
                val tail = text.substring(m.range.last + 1)
                    .let { Regex("""^\s*(\d+)?\s*(§\s*\d+)?""").find(it) }
                val extra = listOfNotNull(
                    tail?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let { "pág. $it" },
                    tail?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
                ).joinToString(" ").trim()
                add(
                    DetectedRef(
                        m.value.trim(), Kind.MAGAZINE, "w",
                        "w|$year|$month|$day",
                        "A Sentinela, ${day}º de $monthName de $year ($edition)" +
                            (if (extra.isNotEmpty()) " • $extra" else "")
                    )
                )
            }
        }
        WP_CODE_RE.findAll(text).forEach { m ->
            val year = fullYear(m.groupValues[1].toInt())
            val num = m.groupValues[2].ifEmpty { m.groupValues[3] }.toIntOrNull()
            if (num != null) {
                add(
                    DetectedRef(
                        m.value.trim(), Kind.MAGAZINE, "wp",
                        "wp|$year|$num", "A Sentinela N.º $num $year (pública)"
                    )
                )
            }
        }
        W_CODE_RE.findAll(text).forEach { m ->
            val year = fullYear(m.groupValues[1].toInt())
            val num = m.groupValues[2].toInt()
            add(
                DetectedRef(
                    m.value.trim(), Kind.MAGAZINE, "w",
                    "w|$year|$num", "A Sentinela $num/$year"
                )
            )
        }
        G_CODE_RE.findAll(text).forEach { m ->
            // evita casar "Gên 1:26" etc — exige ausência de ':' logo após
            val after = text.substring(m.range.last + 1).trimStart()
            if (after.startsWith(":")) return@forEach
            val month = m.groupValues[1].toInt()
            var year = m.groupValues[2].toInt()
            if (year < 100) year = fullYear(year)
            add(
                DetectedRef(
                    m.value.trim(), Kind.MAGAZINE, "g",
                    "g|$year|$month", "Despertai! $month/$year"
                )
            )
        }
        val norm = normalizeText(text)
        val bookKeys = mutableSetOf<String>()
        SYMBOL_REF_RE.findAll(text).forEach { m ->
            val sym = m.groupValues[1].lowercase()
            if (PubCatalog.isSymbol(sym)) {
                bookKeys += sym
                add(
                    DetectedRef(
                        m.value.trim(), Kind.BOOK, sym,
                        "book|$sym", PubCatalog.titleOf(sym) ?: sym
                    )
                )
            }
        }
        // livro + estudo + parágrafo: "jr 27 § 22" (validado no catálogo)
        Regex("""\b([a-z]{2,4})\s+(\d{1,3})\s*§\s*(\d{1,3})\b""").findAll(text).forEach { m ->
            val sym = m.groupValues[1].lowercase()
            if (PubCatalog.isSymbol(sym)) {
                bookKeys += sym
                add(
                    DetectedRef(
                        m.value.trim(), Kind.BOOK, sym,
                        "book|$sym",
                        "${PubCatalog.titleOf(sym) ?: sym} (estudo ${m.groupValues[2]})"
                    )
                )
            }
        }
        // sigla + número avulsos: "lff 27", "(jy 15)" — fora códigos de revista
        // (lookahead final: \b impediria consumir o ")" e quebraria o balanceamento)
        Regex("""\(?\b([a-z]{2,4}(?:-[12])?)\s+(\d{1,3})\)?(?![a-z0-9])""").findAll(text).forEach { m ->
            val raw = m.value.trim()
            val balanced = raw.startsWith("(") == raw.endsWith(")")
            if (!balanced) return@forEach
            val sym = m.groupValues[1].lowercase()
            if (sym in setOf("w", "g", "wp", "gn")) return@forEach
            if (sym.length < 3 && !raw.startsWith("(")) return@forEach
            if (!PubCatalog.isSymbol(sym)) return@forEach
            bookKeys += sym
            add(
                DetectedRef(
                    raw, Kind.BOOK, sym,
                    "book|$sym",
                    "${PubCatalog.titleOf(sym) ?: sym} (estudo ${m.groupValues[2]})"
                )
            )
        }
        // título integral: vale com palavra-guia colada antes ("veja o livro X")
        // ou sufixo de estudo colado depois ("X, capítulo 5").
        // Frase comum ("viver para sempre") ou instrução ("obter conhecimento")
        // sozinhas não viram citação; "(5 min)" é duração, não estudo.
        for ((normTitle, sym) in PubCatalog.titleIndex()) {
            if (sym in bookKeys) continue
            // qualquer ocorrência guiada/estudada vale
            val cited = Regex("""\b$normTitle\b""").findAll(norm).any { m ->
                val before = norm.substring((m.range.first - 20).coerceAtLeast(0), m.range.first)
                val after = norm.substring(
                    (m.range.last + 1).coerceAtMost(norm.length),
                    (m.range.last + 21).coerceAtMost(norm.length)
                )
                Regex(
                    """(livro|brochura|publicacao|obra|revista|apostila|ver|veja|ler|leia|estud\w*|consult\w*)\s*$"""
                ).containsMatchIn(before) ||
                    Regex(
                        """^\s*\(?(cap|licao|pag|parag|[0-9]+(?!\s*min))"""
                    ).containsMatchIn(after)
            }
            if (!cited) continue
            bookKeys += sym
            add(
                DetectedRef(
                    PubCatalog.titleOf(sym) ?: sym, Kind.BOOK, sym,
                    "book|$sym", PubCatalog.titleOf(sym) ?: sym
                )
            )
        }
        BOOK_NAMES.forEach { (name, key) ->
            if (key in bookKeys) return@forEach // sigla já detectou
            if (norm.contains(name)) {
                val title = BASE_PUBS.firstOrNull { it.slot == key }?.title
                    ?: PubCatalog.titleOf(key)
                    ?: name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                add(DetectedRef(name, Kind.BOOK, key, "book|$key", title))
            }
        }
        return out
    }

    /**
     * Meses (MM) candidatos para uma edição numerada da Sentinela pública (wp),
     * pois o nome do arquivo traz ano+mês (ex: wp_T_201909 = N.º 3 de 2019).
     * Calendário: 2016-2017 bimestral (6/ano: jan,mar,mai,jul,set,nov);
     * 2018-2021 trimestral (3/ano: jan,mai,set); 2022+ anual (N.º 1).
     * Retorna meses de início e fim do bimestre/trimestre quando conhecidos.
     */
    fun wpIssueMonths(year: Int, num: Int): List<String> {
        val starts: List<Int> = when {
            year in 2016..2017 && num in 1..6 -> listOf(2 * num - 1)
            year in 2018..2021 && num in 1..3 -> listOf(listOf(1, 5, 9)[num - 1])
            year >= 2022 && num == 1 -> (1..12).toList()
            else -> return emptyList()
        }
        return starts.flatMap { s -> listOf(s, (s + 1).coerceAtMost(12)) }
            .distinct()
            .map { it.toString().padStart(2, '0') }
    }

    /** Casa anexo com a edição exata da referência. */
    fun matchEdition(ref: DetectedRef, attachments: List<AttachmentEntity>): AttachmentEntity? {        if (ref.kind == Kind.BOOK) {
            // slot base primeiro, depois título no nome do arquivo
            attachments.firstOrNull { it.baseSlot == ref.pubKey && it.indexed }?.let { return it }
            val normKey = normalizeText(ref.pubKey)
            val titleNorm = PubCatalog.titleOf(ref.pubKey)?.let { normalizeText(it) }.orEmpty()
            return attachments.firstOrNull { a ->
                if (!a.indexed) return@firstOrNull false
                val nf = normalizeText(a.fileName)
                val tokens = nf.split(" ").toSet()
                // "Seja Feliz para Sempre.pdf" (sem sigla no nome)
                val titleHit = titleNorm.length >= 4 &&
                    (if (' ' in titleNorm) nf.contains(titleNorm) else tokens.contains(titleNorm))
                tokens.contains(ref.pubKey) || // lff_T.pdf, bhs_T.epub…
                    nf.contains(normKey) ||
                    titleHit ||
                    (ref.pubKey == "be" && matchBaseSlot(a.fileName) == "be") ||
                    (ref.pubKey == "th" && matchBaseSlot(a.fileName) == "th")
            }
        }
        // revista: código + ano + número/mês (+ dia, se houver) no nome do arquivo
        val parts = ref.editionKey.split("|")
        if (parts.size < 3) return null
        val code = parts[0].trimEnd('n') // gn -> g para matching de arquivo
        val year = parts[1]
        val num = parts[2]
        val day = parts.getOrNull(3)
        val year2 = (year.toIntOrNull() ?: return null) % 100
        return attachments.firstOrNull { a ->
            if (!a.indexed) return@firstOrNull false
            val n = normalizeText(a.fileName)
            val tokens = n.split(" ").toSet()
            // título da revista no nome ("A Sentinela N.º 3 2019.pdf")
            val titleHit = when (parts[0]) {
                "w", "wp" -> n.contains("sentinela")
                "g", "gn" -> n.contains("despertai")
                "mwb" -> n.contains("mwb") || n.contains("apostila")
                else -> false
            }
            val hasCode = titleHit ||
                tokens.any { it == code || it == parts[0] || it.startsWith(code) }
            val mm = num.toIntOrNull()?.toString()?.padStart(2, '0') ?: num
            val yy = year2.toString().padStart(2, '0')
            // ns sem espaços: "wp19_3" -> "wp193" casa ano2+número
            val ns = n.replace(" ", "")
            // wp pública: o arquivo traz ano+mês da edição (ex: wp_T_201909 = N.º 3/2019)
            val wpMonths = if (parts[0] == "wp") {
                wpIssueMonths(year.toIntOrNull() ?: 0, num.toIntOrNull() ?: 0)
            } else emptyList()
            var hasYear = tokens.any { it == year || it == yy } ||
                n.contains(year + mm) || n.contains(mm + yy) ||
                ns.contains(yy + num) || ns.contains(yy + mm) ||
                wpMonths.any { m -> n.contains(year + m) || ns.contains(yy + m) } ||
                (code == "g" && n.contains(year + mm))
            if (day != null) {
                // edição datada antiga: exige o dia no nome (w19990501, w99 15-5…)
                val dd = day.toIntOrNull()?.toString()?.padStart(2, '0') ?: day
                hasYear = hasYear && (n.contains(year + mm + dd) || n.contains(dd) ||
                    tokens.any { it == day || it == dd })
            }
            val hasNum = tokens.any { it == num || it == num.toIntOrNull()?.toString() } || hasYear
            hasCode && hasYear && hasNum
        }
    }

    private const val REVISTAS = "https://www.jw.org/pt/biblioteca/revistas/"

    /**
     * URL oficial de download para uma referência faltante.
     * Retorna (url, dica, exata): exata=false usa página genérica (botão "Procurar").
     * Todos os padrões exatos foram verificados no ar.
     */
    fun downloadUrl(ref: DetectedRef): Triple<String, String, Boolean> {
        if (ref.kind == Kind.BOOK) {
            BASE_PUBS.firstOrNull { it.slot == ref.pubKey }?.let {
                return Triple(it.landingUrl, "Baixe o livro na página oficial.", true)
            }
            if (PubCatalog.entryOf(ref.pubKey) != null) {
                // finder resolve a sigla (verificado); página tem todos os formatos
                return Triple(
                    "https://www.jw.org/finder?wtlocale=T&pub=${ref.pubKey}&srcid=share",
                    "Abre a página da publicação no site oficial.", true
                )
            }
            val q = URLEncoder.encode(ref.label, "UTF-8")
            return Triple("$JW_SEARCH$q", "Busque a publicação no site oficial.", false)
        }
        val parts = ref.editionKey.split("|")
        if (parts.size < 3) {
            return Triple(MAGAZINES_LANDING, "Procure a edição ${ref.label} e baixe em PDF ou EPUB.", false)
        }
        return when (parts[0]) {
            // Despertai! mensal antiga: g201308 (verificado)
            "g" -> {
                val mm = parts[2].toIntOrNull()?.toString()?.padStart(2, '0') ?: parts[2]
                Triple(
                    REVISTAS + "g" + parts[1] + mm + "/",
                    "Abre a edição ${ref.label} com download.", true
                )
            }
            // Despertai! numerada nova: despertai-no1-2024 (verificado)
            "gn" -> Triple(
                "${REVISTAS}despertai-no${parts[2]}-${parts[1]}/",
                "Abre a edição ${ref.label} com download.", true
            )
            // Sentinela estudo mensal: sentinela-estudo-dezembro-2024 (verificado 2024 e 2019)
            // Sentinela antiga datada dia/mês: w19990501 (verificado)
            "w" -> {
                val m = parts[2].toIntOrNull() ?: 0
                val d = parts.getOrNull(3)?.toIntOrNull() ?: 0
                if (parts.size == 4 && m in 1..12 && d in 1..31) Triple(
                    "${REVISTAS}w${parts[1]}${m.toString().padStart(2, '0')}${d.toString().padStart(2, '0')}/",
                    "Abre a edição ${ref.label} com download.", true
                )
                else if (parts.size == 3 && m in 1..12) Triple(
                    REVISTAS + "sentinela-estudo-" + monthSlug(m) + "-de-" + parts[1] + "/",
                    "Abre a edição ${ref.label} com download.", true
                )
                else Triple(MAGAZINES_LANDING, "Procure a edição ${ref.label} e baixe em PDF ou EPUB.", false)
            }
            // Sentinela pública numerada recente: sentinela-no1-2024 (verificado)
            // Antigas (no3-2019-set-out) têm sufixo inderivável -> fallback honesto
            "wp" -> {
                val y = parts[1].toIntOrNull() ?: 0
                if (y >= 2022) Triple(
                    "${REVISTAS}sentinela-no${parts[2]}-$y/",
                    "Abre a edição ${ref.label} com download.", true
                )
                else Triple(
                    MAGAZINES_LANDING,
                    "Edições antigas têm endereço próprio: procure ${ref.label} e baixe em PDF ou EPUB.", false
                )
            }
            else -> Triple(MAGAZINES_LANDING, "Procure a edição ${ref.label} e baixe em PDF ou EPUB.", false)
        }
    }

    fun checkAll(text: String, attachments: List<AttachmentEntity>): List<RefStatus> =
        resolve(detect(text), attachments)

    /** Une refs da nota + do esboço sem duplicar edição. Puro/testável. */
    fun unionRefs(a: List<DetectedRef>, b: List<DetectedRef>): List<DetectedRef> {
        val seen = mutableSetOf<String>()
        return (a + b).filter { seen.add(it.editionKey) }
    }

    /** Livro bíblico normalizado -> rótulo (para detecção de versículos). */
    private val BIBLE_BOOKS: Map<String, String> = mapOf(
        "gen" to "Gênesis", "genesis" to "Gênesis",
        "ex" to "Êxodo", "exodo" to "Êxodo",
        "lev" to "Levítico", "levitico" to "Levítico",
        "num" to "Números", "numeros" to "Números",
        "deut" to "Deuteronômio", "deuteronomio" to "Deuteronômio",
        "jos" to "Josué", "josue" to "Josué",
        "jz" to "Juízes", "juizes" to "Juízes",
        "rt" to "Rute", "rute" to "Rute",
        "1sm" to "1 Samuel", "1samuel" to "1 Samuel",
        "2sm" to "2 Samuel", "2samuel" to "2 Samuel",
        "1rs" to "1 Reis", "1reis" to "1 Reis",
        "2rs" to "2 Reis", "2reis" to "2 Reis",
        "1cr" to "1 Crônicas", "1cronicas" to "1 Crônicas",
        "2cr" to "2 Crônicas", "2cronicas" to "2 Crônicas",
        "ed" to "Esdras", "esdras" to "Esdras",
        "ne" to "Neemias", "neemias" to "Neemias",
        "est" to "Ester", "ester" to "Ester",
        "jo" to "João", "joao" to "João",
        "sal" to "Salmos", "salmos" to "Salmos", "salmo" to "Salmos",
        "pro" to "Provérbios", "proverbios" to "Provérbios",
        "ecl" to "Eclesiastes", "eclesiastes" to "Eclesiastes",
        "cant" to "Cânticos", "cantares" to "Cânticos",
        "is" to "Isaías", "isaias" to "Isaías",
        "jr" to "Jeremias", "jeremias" to "Jeremias",
        "lam" to "Lamentações", "lamentacoes" to "Lamentações",
        "ez" to "Ezequiel", "ezequiel" to "Ezequiel",
        "dn" to "Daniel", "daniel" to "Daniel",
        "os" to "Oseias", "oseias" to "Oseias",
        "jl" to "Joel", "joel" to "Joel",
        "am" to "Amós", "amos" to "Amós",
        "ob" to "Obadias", "obadias" to "Obadias",
        "jn" to "Jonas", "jonas" to "Jonas",
        "mq" to "Miqueias", "miqueias" to "Miqueias",
        "hc" to "Habacuque", "habacuque" to "Habacuque",
        "sof" to "Sofonias", "sofonias" to "Sofonias",
        "ag" to "Ageu", "ageu" to "Ageu",
        "zc" to "Zacarias", "zacarias" to "Zacarias",
        "ml" to "Malaquias", "malaquias" to "Malaquias",
        "mt" to "Mateus", "mateus" to "Mateus",
        "mc" to "Marcos", "marcos" to "Marcos",
        "lc" to "Lucas", "lucas" to "Lucas",
        "at" to "Atos", "atos" to "Atos",
        "rm" to "Romanos", "romanos" to "Romanos",
        "1co" to "1 Coríntios", "1corintios" to "1 Coríntios",
        "2co" to "2 Coríntios", "2corintios" to "2 Coríntios",
        "gl" to "Gálatas", "galatas" to "Gálatas",
        "ef" to "Efésios", "efesios" to "Efésios",
        "fp" to "Filipenses", "filipenses" to "Filipenses",
        "cl" to "Colossenses", "colossenses" to "Colossenses",
        "1ts" to "1 Tessalonicenses", "1tessalonicenses" to "1 Tessalonicenses",
        "2ts" to "2 Tessalonicenses", "2tessalonicenses" to "2 Tessalonicenses",
        "1tm" to "1 Timóteo", "1timoteo" to "1 Timóteo",
        "2tm" to "2 Timóteo", "2timoteo" to "2 Timóteo",
        "tt" to "Tito", "tito" to "Tito",
        "fm" to "Filemom", "filemom" to "Filemom",
        "hb" to "Hebreus", "hebreus" to "Hebreus",
        "tg" to "Tiago", "tiago" to "Tiago",
        "1pe" to "1 Pedro", "1pedro" to "1 Pedro",
        "2pe" to "2 Pedro", "2pedro" to "2 Pedro",
        "1jo" to "1 João", "1joao" to "1 João",
        "2jo" to "2 João", "2joao" to "2 João",
        "3jo" to "3 João", "3joao" to "3 João",
        "jd" to "Judas", "judas" to "Judas",
        "ap" to "Apocalipse", "apocalipse" to "Apocalipse"
    )

    /** Menção a versículo bíblico (ex: "Gên 1:26"). Textos bíblicos não viram publicação. */
    data class BibleRef(val bookNorm: String, val label: String, val chapter: Int, val verse: Int)
    /** Detecta menções a versículos (puro/testável). */
    fun detectBible(text: String): List<BibleRef> {
        val out = mutableListOf<BibleRef>()
        val seen = mutableSetOf<String>()
        val re = Regex("""\b((?:[1-3]\s+)?[a-zà-ÿ]+)\s+(\d{1,3})\s*:\s*(\d{1,3})\b""")
        for (m in re.findAll(text.lowercase())) {
            val key = normalizeText(m.groupValues[1]).replace(" ", "")
            val label = BIBLE_BOOKS[key] ?: continue
            val ref = BibleRef(key, label, m.groupValues[2].toInt(), m.groupValues[3].toInt())
            // "Jo 3:16" e "João 3:16" são a mesma menção
            if (seen.add("$label|${ref.chapter}|${ref.verse}")) out += ref
        }
        return out
    }

    /** Capítulo/lição/estudo citado na menção (ex: "lff cap. 5" -> ("cap", 5)). Puro/testável. */
    data class ChapterRef(val kind: String, val number: Int)

    fun chapterOf(raw: String): ChapterRef? {
        // "§" não sobrevive à normalização: trata no texto cru
        Regex("""§\s*(\d{1,3})""").find(raw)?.let {
            return ChapterRef("paragrafo", it.groupValues[1].toInt())
        }
        val t = normalizeText(raw)
        val m = Regex("""\b(capitulo|cap|licao|estudo|paragrafo)\s*\.?\s*(\d{1,3})\b""").find(t)
            ?: return null
        val kind = when {
            m.groupValues[1].startsWith("cap") -> "cap"
            m.groupValues[1].startsWith("lic") -> "licao"
            m.groupValues[1].startsWith("est") -> "estudo"
            else -> "paragrafo"
        }
        return ChapterRef(kind, m.groupValues[2].toInt())
    }
    /** Resolve refs já detectadas (ex: salvas no esboço) contra os anexos atuais. */
    fun resolve(detected: List<DetectedRef>, attachments: List<AttachmentEntity>): List<RefStatus> {
        return detected.map { ref ->
            val hit = matchEdition(ref, attachments)
            if (hit != null) {
                RefStatus(ref, true, hit.fileName)
            } else {
                val (url, hint, exact) = downloadUrl(ref)
                val api = JwMediaApi.apiQuery(ref.editionKey)
                val probe = probeNeeded(ref)
                RefStatus(ref, false, null, url, hint, exact, api?.first, api?.second,
                    probe?.first, probe?.second)
            }
        }
    }

    /** Numeradas (wp/gn) sem código de edição derivável: (pub, ano) para sondar via API. */
    private fun probeNeeded(ref: DetectedRef): Pair<String, Int>? {
        val p = ref.editionKey.split("|")
        if (ref.kind != Kind.MAGAZINE || p.size != 3) return null
        val year = p[1].toIntOrNull() ?: return null
        return when (p[0]) {
            "wp" -> "wp" to year
            "gn" -> "g" to year
            else -> null
        }
    }

    /** Serializa refs detectadas para guardar no esboço (JSON manual, sem org.json). */
    fun detectedToJson(refs: List<DetectedRef>): String {
        return refs.joinToString(",", "[", "]") {
            "{\"r\":\"${esc(it.raw)}\",\"k\":\"${it.kind.name}\"," +
                "\"p\":\"${esc(it.pubKey)}\",\"e\":\"${esc(it.editionKey)}\",\"l\":\"${esc(it.label)}\"}"
        }
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    fun detectedFromJson(json: String): List<DetectedRef> {
        return try {
            val re = Regex("""\{"r":"((?:[^"\\]|\\.)*)","k":"(MAGAZINE|BOOK)","p":"((?:[^"\\]|\\.)*)","e":"((?:[^"\\]|\\.)*)","l":"((?:[^"\\]|\\.)*)"\}""")
            re.findAll(json).map { m ->
                fun un(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
                DetectedRef(
                    un(m.groupValues[1]),
                    Kind.valueOf(m.groupValues[2]),
                    un(m.groupValues[3]), un(m.groupValues[4]), un(m.groupValues[5])
                )
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
