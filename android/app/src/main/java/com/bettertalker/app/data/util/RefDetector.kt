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
                add(
                    DetectedRef(
                        m.value.trim(), Kind.MAGAZINE, "w",
                        "w|$year|$month|$day",
                        "A Sentinela, ${day}º de $monthName de $year ($edition)"
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

    /** Casa anexo com a edição exata da referência. */
    fun matchEdition(ref: DetectedRef, attachments: List<AttachmentEntity>): AttachmentEntity? {
        if (ref.kind == Kind.BOOK) {
            // slot base primeiro, depois título no nome do arquivo
            attachments.firstOrNull { it.baseSlot == ref.pubKey && it.indexed }?.let { return it }
            val normKey = normalizeText(ref.pubKey)
            return attachments.firstOrNull { a ->
                if (!a.indexed) return@firstOrNull false
                val tokens = normalizeText(a.fileName).split(" ").toSet()
                tokens.contains(ref.pubKey) || // lff_T.pdf, bhs_T.epub…
                    normalizeText(a.fileName).contains(normKey) ||
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
            val hasCode = tokens.any { it == code || it == parts[0] || it.startsWith(code) }
            val mm = num.toIntOrNull()?.toString()?.padStart(2, '0') ?: num
            val yy = year2.toString().padStart(2, '0')
            var hasYear = tokens.any { it == year || it == yy } ||
                n.contains(year + mm) || n.contains(mm + yy) ||
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
