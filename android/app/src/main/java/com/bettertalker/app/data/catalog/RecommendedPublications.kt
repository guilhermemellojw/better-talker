package com.bettertalker.app.data.catalog

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.detectSymbol

/**
 * Catálogo curado de publicações recomendadas (BYOD: só metadados + links
 * oficiais; o usuário baixa na WebView/página oficial). Títulos vêm do
 * [PubCatalog]; o [downloadUrl] é a **página específica da publicação** no
 * jw.org (com opções de download) — nunca a WOL.
 */
data class RecommendedPub(
    val symbol: String,
    val category: String,
    /** Página oficial da publicação no jw.org (verificada por HTTP). */
    val downloadUrl: String,
) {
    val title: String get() = PubCatalog.titleOf(symbol) ?: symbol

    /** Página WOL (leitura/estudo) — secundária, nunca destino do Baixar. */
    val pageUrl: String get() = PubCatalog.entryOf(symbol)?.wol.orEmpty()

    /** Página de downloads/formatos (só as bases be/th têm URL de formatos). */
    val formatsUrl: String? get() = BASE_PUBS.firstOrNull { it.slot == symbol }?.downloadsUrl
}

object RecommendedPublications {
    const val BIBLE = "Bíblia"
    const val AIDS = "Apostilas"
    const val INSTRUCTIONS = "Instruções"
    const val RESEARCH = "Pesquisa"
    const val MAGAZINES = "Revistas"
    const val BOOKS = "Livros"

    // Páginas específicas no jw.org (todas verificadas: 200).
    private const val NWTSTY = "https://www.jw.org/pt/biblioteca/biblia/biblia-de-estudo/"
    private const val TH = "https://www.jw.org/pt/biblioteca/brochuras/leitura-e-ensino/"
    private const val BE = "https://www.jw.org/pt/biblioteca/livros/Beneficie-se-da-Escola-do-Minist%C3%A9rio-Teocr%C3%A1tico/"
    private const val LMD = "https://www.jw.org/pt/biblioteca/brochuras/ame-pessoas-faca-discipulos/"
    private const val S38 = "https://www.jw.org/pt/biblioteca/orientacoes/Instru%C3%A7%C3%B5es-para-a-Reuni%C3%A3o-Nossa-Vida-e-Minist%C3%A9rio-Crist%C3%A3o/Instru%C3%A7%C3%B5es-para-a-reuni%C3%A3o-Nossa-Vida-e-Minist%C3%A9rio-Crist%C3%A3o"
    private const val DX = "https://www.jw.org/pt/biblioteca/indices/"
    private const val IT = "https://www.jw.org/pt/biblioteca/livros/estudo-perspicaz-das-escrituras/"
    private const val MAG = "https://www.jw.org/pt/biblioteca/revistas/"
    private const val MWB = "https://www.jw.org/pt/biblioteca/jw-apostila-do-mes/"
    private const val RR = "https://www.jw.org/pt/biblioteca/livros/adoracao-pura/"
    private const val IA = "https://www.jw.org/pt/biblioteca/livros/"

    /** Top do discovery (curadoria fixa por release). */
    val ALL: List<RecommendedPub> = listOf(
        RecommendedPub("nwtsty", BIBLE, NWTSTY),
        RecommendedPub("th", AIDS, TH),
        RecommendedPub("be", AIDS, BE),
        RecommendedPub("lmd", AIDS, LMD),
        RecommendedPub("s38", INSTRUCTIONS, S38),
        RecommendedPub("dx", RESEARCH, DX),
        RecommendedPub("it-1", RESEARCH, IT),
        RecommendedPub("it-2", RESEARCH, IT),
        RecommendedPub("it-3", RESEARCH, IT),
        RecommendedPub("w", MAGAZINES, MAG),
        RecommendedPub("g", MAGAZINES, MAG),
        RecommendedPub("mwb", MAGAZINES, MWB),
        RecommendedPub("rr", BOOKS, RR),
        RecommendedPub("ia", BOOKS, IA),
    )

    /** Agrupado na ordem de declaração das categorias (sem grupos vazios). */
    fun byCategory(): List<Pair<String, List<RecommendedPub>>> =
        listOf(BIBLE, AIDS, INSTRUCTIONS, RESEARCH, MAGAZINES, BOOKS)
            .map { cat -> cat to ALL.filter { it.category == cat } }
            .filter { it.second.isNotEmpty() }
}

/**
 * Casa o arquivo do acervo com o símbolo do catálogo. Aceita edições:
 * `w19.03.pdf` conta como `w`; `g 6/07.pdf` como `g`; `it-1.pdf` como `it-1`.
 * Puro/testável.
 */
internal fun attachmentMatchesSymbol(symbol: String, fileName: String): Boolean {
    val canonical = PubCatalog.resolveSymbol(symbol) ?: symbol
    val name = fileName.lowercase()
    if (name.startsWith("$canonical.") || name.startsWith("${canonical}_") ||
        name.startsWith("$canonical-")
    ) {
        return true
    }
    val detected = detectSymbol(fileName)
    if (detected == canonical) return true
    // Variante compacta (S-38-T_194 → "s38t194"): símbolo sem separadores.
    val compact = name.replace(Regex("[^a-z0-9]"), "")
    val compactCanonical = canonical.replace(Regex("[^a-z0-9]"), "")
    if (compactCanonical.length >= 3 && compact.startsWith(compactCanonical) &&
        compact.length > compactCanonical.length &&
        compact[compactCanonical.length].isLetterOrDigit()
    ) {
        return true
    }
    // Edições (revistas/apostilas): "w19.03", "g 6/07", "mwb24.05".
    return detected.startsWith(canonical) && detected.length > canonical.length &&
        (detected[canonical.length].isDigit() || detected[canonical.length] == ' ')
}

/** Arquivo do acervo que corresponde à publicação (null = não baixada). */
fun findInLibrary(symbol: String, attachments: List<AttachmentEntity>): AttachmentEntity? {
    val canonical = PubCatalog.resolveSymbol(symbol) ?: symbol
    return attachments.firstOrNull { a ->
        a.symbol == canonical || attachmentMatchesSymbol(symbol, a.fileName)
    }
}

/** A publicação já está no acervo? Puro/testável. */
fun isInLibrary(symbol: String, attachments: List<AttachmentEntity>): Boolean =
    findInLibrary(symbol, attachments) != null
