package com.bettertalker.app.data.publication

import com.bettertalker.app.data.util.DocKind
import com.bettertalker.app.data.util.PubCatalog

/**
 * 5.6b: resolve o tipo editorial passado ao builder de unidades.
 *
 * JWPUB usa o tipo do catálogo (book/brochure/magazine/manual...). Revistas
 * datadas ("wp17.09") não estão no mapa por edição e caem em "jwpub"; o
 * builder ainda as reconhece como ARTICLE pelo símbolo. EPUB/PDF/DOCX mantêm
 * o formato para virarem DOCUMENT.
 */
internal fun publicationKindFor(symbol: String, fileKind: DocKind): String =
    if (fileKind == DocKind.JWPUB) {
        PubCatalog.entryOf(symbol)?.kind ?: "jwpub"
    } else {
        fileKind.ext
    }
