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

// ---------- Detecção de formato ----------

enum class DocKind(val ext: String) { PDF("pdf"), EPUB("epub"), DOCX("docx"), RTF("rtf"), ZIP("zip"), TXT("txt"), UNSUPPORTED("bin") }

fun detectKind(fileName: String, file: java.io.File? = null): DocKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    when (ext) {
        "pdf" -> return DocKind.PDF
        "epub" -> return DocKind.EPUB
        "docx" -> return DocKind.DOCX
        "rtf" -> return DocKind.RTF
        "zip" -> return DocKind.ZIP
        "txt", "md" -> return DocKind.TXT
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
    s = s.replace(Regex("\\\\par[d]?"), "\n")
    s = s.replace(Regex("\\\\(tab|line)"), " ")
    s = s.replace(Regex("\\\\u-?\\d+\\??"), " ") // unicode \uN
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
            val hit = keywords.any { kw -> raw.regionMatches(j, kw, 0, kw.length, ignoreCase = true) }
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
    raw.split(Regex("[.!?\\n]+"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.length > 5 }

/** Remove tags XML/HTML genéricas. */
fun stripXml(raw: String): String =
    raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
