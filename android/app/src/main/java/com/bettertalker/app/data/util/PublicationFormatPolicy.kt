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

    /** Ordem por publicação; NWT nunca tenta JWPUB/PDF. */
    fun orderFor(pub: String): List<String> = when (pub.lowercase()) {
        "nwt" -> NWT_ORDER
        "s34", "s-34" -> S34_ORDER
        else -> PUBLICATION_ORDER
    }

    fun isS34File(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.contains("s-34") || n.contains("s34")
    }

    /**
     * Motivo para bloquear o download antes de enfileirar (NWT não-EPUB).
     *
     * O WebView às vezes entrega um nome genérico, então o kind e o slot
     * também podem vir da URL de origem. Sem isso, o EPUB da TNM era
     * bloqueado pelo fallback antigo mesmo com `nwt_T.epub` na URL.
     */
    fun downloadBlockedReason(fileName: String, sourceUrl: String? = null): String? {
        val url = sourceUrl?.substringBefore('?')
        val kind = detectKind(fileName).takeIf { it != DocKind.UNSUPPORTED }
            ?: detectKind(url.orEmpty())
        val slot = matchBaseSlot(fileName) ?: matchBaseSlot(url.orEmpty())
        if (slot == "nwt" && isNwtBlocked(kind.ext)) {
            return nwtBlockedMessage(kind.ext)
        }
        return null
    }

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

    /** v5.3.1: troca de arquivo da TNM não pode aceitar EPUB genérico. */
    fun nwtRepairBlockedReason(
        currentPublicationKey: String?,
        candidateKind: String,
        candidatePublicationKey: String?,
    ): String? {
        if (currentPublicationKey != "nwt") return null
        if (candidateKind.equals("epub", ignoreCase = true) &&
            candidatePublicationKey == "nwt"
        ) return null
        return "A TNM só pode ser substituída por um EPUB da TNM. " +
            "Escolha o arquivo nwt_T.epub (ou o EPUB oficial da Tradução do Novo Mundo)."
    }

    /**
     * v5.3.1: mantém o slot de be/th/s38 quando o arquivo substituto tem nome
     * genérico; a TNM continua nwt (o guard exige candidato nwt).
     */
    fun repairedBaseSlot(
        currentBaseSlot: String?,
        currentPublicationKey: String?,
        candidateBaseSlot: String?,
        candidatePublicationKey: String?,
    ): String? = when {
        currentPublicationKey == "nwt" ->
            if (candidatePublicationKey == "nwt") "nwt" else currentBaseSlot
        candidateBaseSlot != null -> candidateBaseSlot
        else -> currentBaseSlot
    }

    /** Rótulo curto para a UI da Biblioteca. */
    fun libraryBadge(kind: String): String? = publicationWarning(kind)
}
