package com.bettertalker.app.data.util

/**
 * Cauda de unidade de uma citação de revista nomeada:
 * "pág. 6", "pág. 6-7", "pág. 8 parág. 1-2", "§ 5", "§§ 3-5".
 *
 * As regexes nomeadas (Despertai! 08/13, Sentinela número 3 de 2019) param
 * no ano/número; este parser captura a unidade que vem logo depois, sem
 * invadir a próxima frase/referência.
 *
 * Puro/testável.
 */
data class MagazineTail(
    val page: Int? = null,
    val pageEnd: Int? = null,
    val paragraph: Int? = null,
    val paragraphEnd: Int? = null,
) {
    val isEmpty: Boolean
        get() = page == null && pageEnd == null &&
            paragraph == null && paragraphEnd == null

    /** Rótulo curto para a linha detectada ("pág. 8 §1-2"). */
    fun label(): String = buildString {
        page?.let {
            append("pág. ").append(it)
            pageEnd?.takeIf { end -> end > it }?.let { end -> append('-').append(end) }
        }
        paragraph?.let {
            if (isNotEmpty()) append(' ')
            append('§').append(it)
            paragraphEnd?.takeIf { end -> end > it }?.let { end -> append('-').append(end) }
        }
    }
}

private val PAGE_TAIL = Regex(
    """\bp[áa]g(?:ina)?s?\.?\s*(\d{1,4})(?:\s*[-–]\s*(\d{1,4}))?""",
    RegexOption.IGNORE_CASE,
)
private val PARAGRAPH_TAIL = Regex(
    """(?:\bpar[áa]g(?:rafo)?s?\.?|§{1,2})\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?""",
    RegexOption.IGNORE_CASE,
)

/**
 * Lê a cauda a partir de [lastIndex] (fim do match da revista). Limita a
 * janela para não capturar página/§ de outra referência da mesma linha.
 */
fun parseMagazineTail(text: String, lastIndex: Int): MagazineTail {
    if (lastIndex >= text.length) return MagazineTail()
    val start = (lastIndex + 1).coerceAtLeast(0)
    // 80 chars cobrem "pág. 8 parág. 1-2" com folga; o corte no primeiro
    // delimitador forte evita atravessar outra citação.
    val raw = text.substring(start, (start + 80).coerceAtMost(text.length))
        .replace('\u00A0', ' ')   // DOCX usa NBSP entre "pág." e o número
        .replace("\u200B", "")   // zero-width em ranges ("22-​23")
        .replace("\uFEFF", "")
    val tail = raw.takeWhile { it != ';' && it != '|' && it != '\n' }
    val pageMatch = PAGE_TAIL.find(tail)
    val parMatch = PARAGRAPH_TAIL.find(tail)
    val page = pageMatch?.groupValues?.get(1)?.toIntOrNull()
    val pageEnd = pageMatch?.groupValues?.get(2)?.toIntOrNull()
    val paragraph = parMatch?.groupValues?.get(1)?.toIntOrNull()
    val paragraphEnd = parMatch?.groupValues?.get(2)?.toIntOrNull()
    return MagazineTail(
        page = page,
        pageEnd = pageEnd?.takeIf { page != null && it > page },
        paragraph = paragraph,
        paragraphEnd = paragraphEnd?.takeIf { paragraph != null && it > paragraph },
    )
}
