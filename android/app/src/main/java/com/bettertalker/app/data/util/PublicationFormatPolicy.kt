package com.bettertalker.app.data.util

/**
 * Política de formatos do acervo (v5.3.1).
 *
 * - Publicações: JWPUB > EPUB > PDF. PDF é o último recurso e entra como
 *   "PDF limitado" (sem §/página estruturados); a UI oferece troca/re-download.
 * - S-34: JWPUB > DOCX; PDF só como fallback, com aviso de estrutura limitada.
 * - TNM/NWT: só EPUB é indexável por versículo. JWPUB/PDF da TNM são
 *   bloqueados; `baseReady("nwt")` só considera EPUB.
 *
 * Puro/testável. Não decide download de rede.
 */
object PublicationFormatPolicy {

    val PUBLICATION_ORDER: List<String> = listOf("JWPUB", "EPUB", "PDF")
    val S34_ORDER: List<String> = listOf("JWPUB", "DOCX", "PDF")
    val NWT_ORDER: List<String> = listOf("EPUB")

    fun publicationWarning(kind: String): String? =
        if (kind.equals("pdf", ignoreCase = true)) {
            "PDF limitado: não tem página/§ estruturados. Prefira JWPUB ou EPUB."
        } else {
            null
        }

    fun s34Warning(kind: String): String? =
        if (kind.equals("pdf", ignoreCase = true)) {
            "PDF importado como fallback. Para melhor estrutura, use JWPUB ou DOCX."
        } else {
            null
        }

    fun isNwtBlocked(kind: String): Boolean {
        val k = kind.lowercase()
        return k != "epub"
    }

    fun nwtBlockedMessage(kind: String): String =
        "A TNM só é indexada por versículo em EPUB. " +
            "O formato ${kind.uppercase()} da TNM não é aceito; baixe o EPUB."

    /** Anexo é TNM e está pronto para versículo? */
    fun isNwtVerseReady(baseSlot: String?, kind: String, indexed: Boolean): Boolean =
        baseSlot == "nwt" && indexed && kind.equals("epub", ignoreCase = true)

    /** Rótulo curto para a UI da Biblioteca. */
    fun libraryBadge(kind: String): String? = publicationWarning(kind)
}
