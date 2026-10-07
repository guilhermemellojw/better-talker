package com.bettertalker.app.data.util

import java.text.Normalizer
import java.util.UUID

fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

fun normalizeText(raw: String): String {
    val noAccent = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        .replace("[\\u0300-\\u036f]".toRegex(), "")
    return noAccent.replace("[^a-z0-9\\s]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ").trim()
}

/**
 * Palavras funcionais PT que não carregam tópico (artigos, preposições,
 * pronomes, verbos genéricos). Usadas para não gastar o orçamento de
 * palavras da busca nem viciar o casamento de seções.
 */
val STOPWORDS_PT = setOf(
    "o", "a", "os", "as", "um", "uma", "uns", "umas",
    "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
    "por", "para", "com", "sem", "sob", "sobre", "entre", "ate",
    "que", "se", "como", "ser", "foi", "for", "era", "sao",
    "ter", "tem", "estar", "esta", "haver", "ha",
    "ficar", "fica", "poder", "pode", "fazer", "faz",
    "dizer", "diz", "ver", "dar",
    "este", "esta", "isto", "esse", "essa", "isso",
    "aquele", "aquela", "mais", "muito", "pouco",
    "todo", "toda", "todos", "todas", "cada",
    "qual", "quais", "quando", "onde", "porque", "pois", "mas",
    "tambem", "nao", "sera", "serao", "vai", "vao",
    "me", "te", "lhe", "vos", "mim", "ti",
    "ele", "ela", "eles", "elas", "eu", "tu", "voce", "voces",
    "meu", "minha", "seu", "sua"
)

fun splitSentences(normalized: String): List<String> =
    normalized.split(Regex("[.!?\\n]+"))
        .map { it.trim() }
        .filter { it.length > 5 }

fun plainFromMarkdown(md: String): String =
    md.replace(Regex("[#>*`_\\-\\[\\]()!]"), " ")
        .replace(Regex("\\s+"), " ").trim()

fun previewOf(md: String, max: Int = 140): String {
    val plain = plainFromMarkdown(md)
    return if (plain.length <= max) plain else plain.take(max).trimEnd() + "…"
}

/** Link oficial para abrir a referência no site — nunca hospedamos conteúdo. */
fun buildJwUrl(ref: String): String {
    val n = ref.lowercase().trim()
    if (n.startsWith("w") && n.contains(".")) {
        val parts = n.split(".")
        if (parts.size >= 2) {
            val year = parts[0].removePrefix("w")
            val issue = parts[1].filter { it.isDigit() }
            return "https://www.jw.org/finder?wtlocale=T&srcid=share&wfile=$year/$issue"
        }
    }
    return "https://www.jw.org/finder?wtlocale=T&srcid=share&wfile=$n"
}

const val JW_FINDER_HOME = "https://www.jw.org/finder?wtlocale=T&srcid=share"

/** URL de página (reabre WebView) vs arquivo direto (re-enfileira DM). */
fun isPageUrl(url: String): Boolean =
    url.contains("/biblioteca/") || url.contains("finder") || url.contains("busca")

// ---------- Publicações-base do Copilot (slots fixos) ----------
// URLs oficiais verificadas — só landings + páginas de download.
// Nunca fixar URL direta de arquivo (mudam a cada revisão).

data class BasePub(
    val slot: String, // be | th
    val title: String,
    val code: String, // prefixo dos arquivos no DownloadManager (be_, th_)
    val landingUrl: String,
    val downloadsUrl: String,
    val synonyms: List<String> // nomes normalizados para auto-reconhecimento
)

val BASE_PUBS = listOf(
    BasePub(
        slot = "be",
        title = "Beneficie-se da Escola do Ministério Teocrático",
        code = "be_",
        landingUrl = "https://www.jw.org/pt/biblioteca/livros/Beneficie-se-da-Escola-do-Minist%C3%A9rio-Teocr%C3%A1tico/",
        downloadsUrl = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS?output=html&pub=be&fileformat=PDF%2CEPUB%2CJWPUB%2CRTF%2CTXT%2CBRL%2CBES%2CDAISY%2CUPDATEPKG&alllangs=0&langwritten=T&txtCMSLang=T",
        synonyms = listOf("beneficie", "escola do ministerio", "ministerio teocratico")
    ),
    BasePub(
        slot = "th",
        title = "Melhore Sua Leitura e Seu Ensino",
        code = "th_",
        landingUrl = "https://www.jw.org/pt/biblioteca/brochuras/leitura-e-ensino/",
        downloadsUrl = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS?output=html&pub=th&fileformat=PDF%2CEPUB%2CJWPUB%2CRTF%2CTXT%2CBRL%2CBES%2CDAISY%2CUPDATEPKG&alllangs=0&langwritten=T&txtCMSLang=T",
        synonyms = listOf("melhore", "leitura e ensino", "leitura-e-ensino")
    ),
    // T5 (refs): a Bíblia vira base — habilita `baseReady("nwt")` (corpus
    // para refs bíblicas no chat) e o download pelo fluxo de bases.
    BasePub(
        slot = "nwt",
        title = "Tradução do Novo Mundo da Bíblia Sagrada",
        code = "nwt_",
        landingUrl = "https://www.jw.org/pt/biblioteca/biblia/nwt/",
        downloadsUrl = "https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS?output=html&pub=nwt&fileformat=EPUB%2CPDF&alllangs=0&langwritten=T&txtCMSLang=T",
        synonyms = listOf("traducao do novo mundo", "biblia sagrada")
    )
)

/** Casa um nome de arquivo com um slot base (be/th) ou null. */
fun matchBaseSlot(fileName: String): String? {
    val norm = normalizeText(fileName)
    val tokens = norm.split(" ").toSet()
    for (pub in BASE_PUBS) {
        val code = pub.code.trimEnd('_') // be | th
        // prefixo oficial (be_T.pdf -> be t pdf) exige marcador de idioma "t"
        if (tokens.contains(code) && tokens.contains("t")) return pub.slot
        if (pub.synonyms.any { norm.contains(it) }) return pub.slot
    }
    return null
}

/**
 * Detecta o símbolo editorial do arquivo (web-style).
 *
 * 1. Se for be/th via [matchBaseSlot], retorna o slot ("be" ou "th").
 * 2. Revistas com edição no nome: "w19.03", "wp19.03", "g 6/07", "gn 1/24"
 *    (ver [detectMagazineSymbol]).
 * 3. Senão, extrai o prefixo do nome (1-4 letras + separador).
 * 4. Fallback: primeiros 24 chars do nome base.
 */
fun detectSymbol(fileName: String): String {
    matchBaseSlot(fileName)?.let { return it }
    val base = fileName.substringBeforeLast('.').lowercase()
    // Extensão 3.2.3a-fix2: revistas com edição no nome.
    detectMagazineSymbol(base)?.let { return it }
    val prefixMatch = Regex("""^([a-z]{1,4})[\s._-]""").find(base)
    return prefixMatch?.groupValues?.get(1) ?: base.take(24)
}

/**
 * Detecta símbolo editorial de revista no nome do arquivo (base, minúscula,
 * sem extensão). Formatos suportados:
 * - "w94 1/8" / "g93 8/1" / "w82 15.3" → "w94 1/8" / "g93 8/1" / "w82 15/3"
 *   (quinzenal antigo, dia/mês normalizado com "/")
 * - "w19.03" / "w2019.03" / "w24 12" → "w19.03" / "w2019.03" / "w24.12"
 * - "wp19.03" / "wp_T_201909" → "wp19.03" / "wp19.09"
 * - "g 6/07" / "g6/07" → "g 6/07" (espaço normalizado)
 * - "gn 1/24" / "gn1/24" → "gn 1/24"
 * Retorna null se não reconhecer (cai no fallback de [detectSymbol]).
 *
 * DÉBITO TÉCNICO (3.2.3a-fix2/fix3): nomes fora destes padrões caem no fallback
 * take(24); mês fora de 1..12 é rejeitado de propósito (evita falso positivo);
 * variante antiga "w70 106" (ano + página, sem mês/dia) não é reconhecida.
 */
private fun detectMagazineSymbol(base: String): String? {
    // Formato quinzenal antigo: "w94 1/8" / "w82 15.3" / "g93 8/1" (dia/mês).
    Regex("""^(w|g)\s*(\d{2})\s+(\d{1,2})[./](\d{1,2})(?!\d)""").find(base)?.let { m ->
        val day = m.groupValues[3].toIntOrNull() ?: return@let
        val month = m.groupValues[4].toIntOrNull() ?: return@let
        if (day !in 1..31 || month !in 1..12) return@let
        return "${m.groupValues[1]}${m.groupValues[2]} $day/$month"
    }
    // w/wp: ano (YY ou YYYY) + mês. Separadores . / espaço ou hífen.
    Regex("""^(wp?)[\s._-]*(\d{2,4})[./\s-](\d{1,2})(?!\d)""").find(base)?.let { m ->
        val month = m.groupValues[3].toIntOrNull() ?: return@let
        if (month !in 1..12) return@let
        return "${m.groupValues[1]}${m.groupValues[2]}.${month.toString().padStart(2, '0')}"
    }
    // g/gn: mês + ano.
    Regex("""^(gn?)[\s._-]*(\d{1,2})[./\s-](\d{2,4})(?!\d)""").find(base)?.let { m ->
        val month = m.groupValues[2].toIntOrNull() ?: return@let
        if (month !in 1..12) return@let
        return "${m.groupValues[1]} $month/${m.groupValues[3].takeLast(2)}"
    }
    // Formato compacto do jw.org: "w_T_201909" / "wp_T_201909".
    Regex("""^(wp?)[\s._-]*t[\s._-]*(\d{6})(?!\d)""").find(base)?.let { m ->
        val yyyymm = m.groupValues[2]
        val month = yyyymm.substring(4, 6).toIntOrNull() ?: return@let
        if (month !in 1..12) return@let
        return "${m.groupValues[1]}${yyyymm.substring(2, 4)}.${yyyymm.substring(4, 6)}"
    }
    return null
}

/**
 * Monta o ref de um passage no formato do web: "symbol section §n".
 * Se section em branco, omite a seção.
 * Section é truncada em 60 chars.
 */
fun buildRef(symbol: String, section: String, paragraph: Int): String {
    val parts = mutableListOf(symbol)
    if (section.isNotBlank()) parts.add(section.take(60))
    parts.add("§$paragraph")
    return parts.joinToString(" ")
}

// ---------- Detecção de formato ----------

enum class DocKind(val ext: String) { PDF("pdf"), EPUB("epub"), DOCX("docx"), RTF("rtf"), ZIP("zip"), TXT("txt"), JWPUB("jwpub"), UNSUPPORTED("bin") }

fun detectKind(fileName: String, file: java.io.File? = null): DocKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    when (ext) {
        "pdf" -> return DocKind.PDF
        "epub" -> return DocKind.EPUB
        "docx" -> return DocKind.DOCX
        "rtf" -> return DocKind.RTF
        "zip" -> return DocKind.ZIP
        "txt", "md" -> return DocKind.TXT
        "jwpub" -> return DocKind.JWPUB
    }
    // sniffing pelo header quando a extensão falha
    if (file != null && file.exists()) {
        return try {
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            when {
                head[0] == 0x25.toByte() && head[1] == 0x50.toByte() -> DocKind.PDF // %PDF
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> DocKind.ZIP // PK (epub/docx/zip)
                head[0] == 0x7B.toByte() -> DocKind.RTF // {\rtf
                else -> DocKind.UNSUPPORTED
            }
        } catch (_: Exception) { DocKind.UNSUPPORTED }
    }
    return DocKind.UNSUPPORTED
}

/** Remove marcação RTF mantendo texto e quebras. */
fun stripRtf(raw: String): String {
    var s = removeRtfGroups(raw)
    // \uN = caractere unicode (ex: \u243? = ó) — converte em vez de apagar
    s = Regex("""\\u(-?\d+)\??""").replace(s) { m ->
        var n = m.groupValues[1].toIntOrNull() ?: return@replace " "
        if (n < 0) n += 65536
        try {
            n.toChar().toString()
        } catch (_: Exception) {
            " "
        }
    }
    s = s.replace(Regex("\\\\par[d]?"), "\n")
    s = s.replace(Regex("\\\\(tab|line)"), " ")
    s = s.replace(Regex("\\\\'[0-9a-fA-F]{2}"), " ") // hex \'xx
    s = s.replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), " ") // palavras de controle
    s = s.replace(Regex("[{}]"), " ")
    s = s.replace(Regex("\\\\"), " ")
    return s.replace(Regex("[ \\t\\r]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
}

/**
 * Remove grupos RTF binários/de controle (imagens \pict, tabelas de fonte/cor,
 * estilos, metadados) com casamento de chaves — o conteúdo hex vira lixo no índice.
 */
fun removeRtfGroups(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    val n = raw.length
    val keywords = listOf("\\pict", "\\fonttbl", "\\colortbl", "\\stylesheet", "\\info", "\\header", "\\footer")
    while (i < n) {
        if (raw[i] == '{') {
            var j = i + 1
            while (j < n && (raw[j] == ' ' || raw[j] == '\n' || raw[j] == '\r' || raw[j] == '\t')) j++
            // {\*\...} = grupo de destino (metadados) — descarta inteiro
            val starred = j < n && raw[j] == '\\' && j + 1 < n && raw[j + 1] == '*'
            val hit = starred || keywords.any { kw -> raw.regionMatches(j, kw, 0, kw.length, ignoreCase = true) }
            if (hit) {
                var depth = 0
                while (i < n) {
                    if (raw[i] == '{') depth++
                    else if (raw[i] == '}') {
                        depth--
                        if (depth == 0) { i++; break }
                    }
                    i++
                }
                out.append(' ')
                continue
            }
        }
        out.append(raw[i])
        i++
    }
    return out.toString()
}

/** Decodifica bytes: UTF-8 estrito, fallback windows-1252 (acentos de RTF/TXT). */
fun decodeBytes(bytes: ByteArray): String {
    return try {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        String(bytes, charset("windows-1252"))
    }
}

/** Separa frases no texto CRU (antes de normalizar) — normalizar apaga os delimitadores. */
fun splitRawSentences(raw: String): List<String> =
    splitWithSections(raw).map { it.first }

/**
 * Divide em (frase, seção). Seção = último título detectado:
 * linha tipo "Lição 3", "Capítulo 5", marcador "# Título" do extrator de
 * JWPUB (T4), ou linha curta sem pontuação seguida de parágrafo longo.
 * Marcadores === arquivo === resetam.
 */
fun splitWithSections(raw: String): List<Pair<String, String>> =
    splitWithSectionsAndParagraphs(raw).map { it.first to it.second }

/**
 * T4b: divide em (frase, seção, parágrafo). O marcador "§N" emitido pelo
 * extrator de JWPUB (parágrafo numerado do artigo) vale para as frases
 * seguintes até o próximo marcador ou nova seção (artigo/lição). Puro.
 */
fun splitWithSectionsAndParagraphs(raw: String): List<Triple<String, String, Int?>> {
    val out = mutableListOf<Triple<String, String, Int?>>()
    var section = ""
    var paragraph: Int? = null
    // RTF/PDF usam \n simples entre parágrafos — trabalha linha a linha
    val lines = raw.split("\n").map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
    val markerRe = Regex("^===.*===$")
    val parRe = Regex("^§(\\d{1,3})\\s+(.*)$")
    lines.forEachIndexed { idx, rawLine ->
        var line = rawLine
        parRe.find(line)?.let {
            paragraph = it.groupValues[1].toInt()
            line = it.groupValues[2].trim()
        }
        if (markerRe.matches(line)) {
            section = ""
            paragraph = null
            return@forEachIndexed
        }
        // T4: títulos do extrator de JWPUB ("# Gedalias") são fronteiras
        // determinísticas de artigo/lição — antes, o heurístico errava a
        // seção (o verbete "GEDALIAS" caía em "GEADA").
        if (line.startsWith("# ") && line.length > 3) {
            section = line.removePrefix("# ").take(120)
            paragraph = null
            return@forEachIndexed
        }
        val nextLen = lines.getOrNull(idx + 1)?.length ?: 0
        val isLesson = LESSON_RE.containsMatchIn(line)
        val isShortTitle = line.length in 4..80 &&
            line.last().let { it != '.' && it != '?' && it != '!' && it != ':' } &&
            nextLen > 120
        if (isLesson || isShortTitle) {
            section = canonicalSectionLine(line).take(120)
            paragraph = null
            return@forEachIndexed
        }
        line.split(Regex("[.!?]+"))
            .map { it.trim() }
            .filter { it.length > 5 }
            .forEach { out += Triple(it, section, paragraph) }
    }
    return out
}

/** Números por extenso usados em cabeçalhos de capítulo ("CAPÍTULO UM"). */
private val CHAPTER_WORDS = mapOf(
    "um" to 1, "dois" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
    "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10,
    "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14,
    "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18,
    "dezenove" to 19, "vinte" to 20,
)

/** T4: lição/capítulo/estudo/parte/seção com número (dígito ou por extenso). */
private val LESSON_RE = Regex(
    "^(li[cç][aã]o|cap[íi]tulo|estudo|parte|se[cç][aã]o)\\s+" +
        "(\\d{1,3}|" + CHAPTER_WORDS.keys.joinToString("|") { if (it == "tres") "tr[eê]s" else it } + ")\\b",
    RegexOption.IGNORE_CASE
)

/**
 * T4: canonicaliza a linha de lição/capítulo. "CAPÍTULO UM" vira "Capítulo 1"
 * (o número por extenso dos EPUBs não casaria com "jr cap. 2"). Puro/testável.
 */
internal fun canonicalSectionLine(line: String): String {
    val m = LESSON_RE.find(line) ?: return line
    val numRaw = m.groupValues[2]
    val n = numRaw.toIntOrNull() ?: CHAPTER_WORDS[normalizeText(numRaw)] ?: return line
    val word = m.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }
    return "$word $n"
}

/** Remove tags XML/HTML preservando quebras de linha (estrutura de parágrafos). */
fun stripXml(raw: String): String =
    raw.replace(Regex("<[^>]+>"), " ")
        // NOTA: sem \v aqui — em Java \v = whitespace vertical (inclui \n)!
        .replace(Regex("[ \\t\\r\\f]+"), " ")
        .replace(Regex("\n[ \t]*\n(?:[ \t]*\n)*"), "\n\n")
        .trim()
