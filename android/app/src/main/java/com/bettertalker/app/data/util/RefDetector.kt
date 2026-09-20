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
        /** g | w | be | th | nome-normalizado */
        val pubKey: String,
        /** chave de edição exata: "g|2013|8", "w|2019|3", "book|be" */
        val editionKey: String,
        val label: String
    )

    data class RefStatus(
        val ref: DetectedRef,
        val resolved: Boolean,
        val fileName: String? = null,
        val downloadUrl: String = "",
        val hint: String = ""
    )

    // Despertai! 08/13 | Despertai!, 8/2013
    private val AWAKE_RE = Regex(
        """Despertai!?,?\s*(\d{1,2})/(\d{2,4})""",
        RegexOption.IGNORE_CASE
    )
    // Sentinela número 3 de 2019 | A Sentinela, n.º 2 de 2017
    private val WATCHTOWER_RE = Regex(
        """(?:A\s+)?Sentinela,?\s+(?:n\.?(?:º|o|°)?\s*)?(?:n[úu]mero\s+)?(\d{1,2})\s+de\s+(\d{4})""",
        RegexOption.IGNORE_CASE
    )
    // w24.12 | w 24/12
    private val W_CODE_RE = Regex("""\bw\s*(\d{2})[./](\d{1,2})\b""")
    // g 1/24 | g1/24
    private val G_CODE_RE = Regex("""\bg\s*(\d{1,2})/(\d{2,4})\b""")

    /** Livros/brochuras por nome (normalizado) -> chave. */
    private val BOOK_NAMES = listOf(
        "beneficie se da escola do ministerio teocratico" to "be",
        "beneficie se" to "be",
        "escola do ministerio teocratico" to "be",
        "melhore sua leitura e seu ensino" to "th",
        "melhore a sua leitura e o seu ensino" to "th",
        "leitura e ensino" to "th",
        "entenda a biblia" to "entenda-a-biblia"
    )

    const val MAGAZINES_LANDING = "https://www.jw.org/pt/biblioteca/revistas/"
    private const val JW_SEARCH = "https://www.jw.org/pt/busca/?q="

    private fun fullYear(yy: Int): Int = if (yy >= 50) 1900 + yy else 2000 + yy

    fun detect(text: String): List<DetectedRef> {
        val out = mutableListOf<DetectedRef>()
        val seen = mutableSetOf<String>()

        fun add(ref: DetectedRef) {
            val k = ref.editionKey + "|" + ref.raw.lowercase()
            if (seen.add(k)) out += ref
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
        WATCHTOWER_RE.findAll(text).forEach { m ->
            val num = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            add(
                DetectedRef(
                    m.value.trim(), Kind.MAGAZINE, "w",
                    "w|$year|$num", "A Sentinela $num/$year"
                )
            )
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
        BOOK_NAMES.forEach { (name, key) ->
            if (norm.contains(name)) {
                val title = BASE_PUBS.firstOrNull { it.slot == key }?.title
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
                a.indexed && (normalizeText(a.fileName).contains(normKey) ||
                    (ref.pubKey == "be" && matchBaseSlot(a.fileName) == "be") ||
                    (ref.pubKey == "th" && matchBaseSlot(a.fileName) == "th"))
            }
        }
        // revista: código + ano + número/mês precisam aparecer no nome do arquivo
        val (code, year, num) = ref.editionKey.split("|")
        val year2 = (year.toIntOrNull() ?: return null) % 100
        return attachments.firstOrNull { a ->
            if (!a.indexed) return@firstOrNull false
            val n = normalizeText(a.fileName)
            val tokens = n.split(" ").toSet()
            val hasCode = tokens.any { it == code || it.startsWith(code) }
            val hasYear = tokens.any { it == year || it == year2.toString().padStart(2, '0') } ||
                n.contains(year + num.toString().padStart(2, '0')) ||
                n.contains(num.toString().padStart(2, '0') + year2.toString().padStart(2, '0')) ||
                (code == "g" && n.contains(year + num.toString().padStart(2, '0')))
            val hasNum = tokens.any { it == num || it == num.toIntOrNull()?.toString() } || hasYear
            hasCode && hasYear && hasNum
        }
    }

    /** URL oficial de download para uma referência faltante. */
    fun downloadUrl(ref: DetectedRef): Pair<String, String> {
        // retorna (url, dica)
        if (ref.kind == Kind.BOOK) {
            BASE_PUBS.firstOrNull { it.slot == ref.pubKey }?.let {
                return it.landingUrl to "Baixe o livro na página oficial."
            }
            val q = URLEncoder.encode(ref.label, "UTF-8")
            return "$JW_SEARCH$q" to "Busque a publicação no site oficial."
        }
        return MAGAZINES_LANDING to "Procure a edição ${ref.label} e baixe em PDF ou EPUB."
    }

    fun checkAll(text: String, attachments: List<AttachmentEntity>): List<RefStatus> {
        return detect(text).map { ref ->
            val hit = matchEdition(ref, attachments)
            if (hit != null) {
                RefStatus(ref, true, hit.fileName)
            } else {
                val (url, hint) = downloadUrl(ref)
                RefStatus(ref, false, null, url, hint)
            }
        }
    }
}
