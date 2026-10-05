package com.bettertalker.app.data.catalog

import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.PubCatalog
import com.bettertalker.app.data.util.detectSymbol

/**
 * Catálogo curado de publicações recomendadas (BYOD: só metadados + links
 * oficiais; o usuário baixa na WebView/página oficial). Títulos e links vêm do
 * [PubCatalog] — fonte única.
 */
data class RecommendedPub(
    val symbol: String,
    val category: String,
) {
    val title: String get() = PubCatalog.titleOf(symbol) ?: symbol

    /**
     * Destino do botão **Baixar**: página do **jw.org** (finder por símbolo) —
     * a WOL só permite leitura, não download. O finder resolve a publicação
     * oficial e oferece os arquivos (mesma API `GETPUBMEDIALINKS`).
     */
    val downloadUrl: String
        get() = "https://www.jw.org/finder?wtlocale=T&srcid=share&wfile=$symbol"

    /** Página WOL (leitura/estudo) — secundária, nunca destino do Baixar. */
    val pageUrl: String get() = PubCatalog.entryOf(symbol)?.wol.orEmpty()

    /** Página de downloads/formatos (só as bases be/th têm URL de formatos). */
    val formatsUrl: String? get() = BASE_PUBS.firstOrNull { it.slot == symbol }?.downloadsUrl
}

object RecommendedPublications {
    const val BIBLE = "Bíblia"
    const val AIDS = "Apostilas"
    const val RESEARCH = "Pesquisa"
    const val MAGAZINES = "Revistas"
    const val BOOKS = "Livros"

    /** Top do discovery (curadoria fixa por release). */
    val ALL: List<RecommendedPub> = listOf(
        RecommendedPub("nwtsty", BIBLE),
        RecommendedPub("th", AIDS),
        RecommendedPub("be", AIDS),
        RecommendedPub("lmd", AIDS),
        RecommendedPub("dx", RESEARCH),
        RecommendedPub("it-1", RESEARCH),
        RecommendedPub("it-2", RESEARCH),
        RecommendedPub("it-3", RESEARCH),
        RecommendedPub("w", MAGAZINES),
        RecommendedPub("g", MAGAZINES),
        RecommendedPub("mwb", MAGAZINES),
        RecommendedPub("rr", BOOKS),
        RecommendedPub("ia", BOOKS),
    )

    /** Agrupado na ordem de declaração das categorias (sem grupos vazios). */
    fun byCategory(): List<Pair<String, List<RecommendedPub>>> =
        listOf(BIBLE, AIDS, RESEARCH, MAGAZINES, BOOKS)
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
