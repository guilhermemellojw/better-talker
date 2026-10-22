package com.bettertalker.app.data.util

/**
 * exp/chat-contrast (2026-10-22) — HTML inline (cores e tags) que vem do
 * material colado/importado (S-34 em .docx, PDF, Word) e quebra a
 * legibilidade no dark mode.
 *
 * Evidência (A34, banco): `speech_sections.contentHtml` com
 * `<span style="color: rgba(123, 31, 162, 1.0); font-size: 18.0px;">` — o
 * roxo do S-34 sobre a surface escura dá **2,13:1** (invisível). A correção
 * do `a198b54` (onSurface no `textStyle` base do rich editor) NÃO cobre spans
 * com cor própria: a lib aplica a cor do span por cima.
 *
 * Duas funções puras:
 * - [sanitizeRichHtmlForTheme]: no dark, cores inline ESCURAS viram a cor do
 *   texto do tema (as claras sobrevivem — o destaque do S-34 no light fica).
 * - [htmlInlineToMarkdown]: o chat é markdown-only; converte o HTML inline
 *   (<b>, <i>, <p>, <br>, <span style=…>) em markdown/texto, sem tags cruas.
 *
 * Sem dependência de Android — testável em JVM.
 */
object HtmlInlineSanitizer {

    /** Limiar de luminância abaixo do qual a cor inline é considerada escura. */
    const val DARK_LUMINANCE_THRESHOLD = 0.30

    /**
     * Normaliza as cores inline escuras do HTML para [fallbackArgb] quando o
     * tema é escuro. No tema claro, devolve o HTML intacto (o destaque do
     * S-34 é intencional).
     */
    fun sanitizeRichHtmlForTheme(html: String, dark: Boolean, fallbackArgb: Long): String {
        if (!dark || html.isEmpty()) return html
        val fallback = argbToCss(fallbackArgb)
        return STYLE_RE.replace(html) { m ->
            val style = m.groupValues[1]
            val newStyle = COLOR_RE.replace(style) { c ->
                val raw = c.groupValues[1].trim()
                val rgb = parseColor(raw) ?: return@replace c.value
                if (relativeLuminance(rgb) < DARK_LUMINANCE_THRESHOLD) {
                    "color: $fallback"
                } else {
                    c.value
                }
            }
            if (newStyle == style) m.value else "style=\"$newStyle\""
        }
    }

    /**
     * Converte HTML inline em markdown/texto: <b>/<strong> → **, <i>/<em> → *,
     * <p>/<div>/<br>/<li> → quebra de linha, as demais tags (inclusive
     * <span style="color:…">) saem, e as entidades HTML são decodificadas.
     */
    fun htmlInlineToMarkdown(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = BLOCK_OPEN_RE.replace(out, "\n")
        out = BLOCK_CLOSE_RE.replace(out, "\n")
        out = out.replace(Regex("(?i)<br\\s*/?>"), "\n")
        out = out.replace(Regex("(?i)<b\\s*>"), "**")
        out = out.replace(Regex("(?i)</b\\s*>"), "**")
        out = out.replace(Regex("(?i)<strong\\s*>"), "**")
        out = out.replace(Regex("(?i)</strong\\s*>"), "**")
        out = out.replace(Regex("(?i)<i\\s*>"), "*")
        out = out.replace(Regex("(?i)</i\\s*>"), "*")
        out = out.replace(Regex("(?i)<em\\s*>"), "*")
        out = out.replace(Regex("(?i)</em\\s*>"), "*")
        // <u>, <s>, <font>, <span …>, <a …> e companhia: some a tag, fica o texto.
        out = TAG_RE.replace(out, "")
        out = decodeEntities(out)
        out = out.replace(Regex("[ \\t]{2,}"), " ")
        out = out.replace(Regex("\\n{3,}"), "\n\n")
        return out.trim()
    }

    // ---------- internos ----------

    private val STYLE_RE = Regex("""(?i)style\s*=\s*"([^"]*)"""")
    private val COLOR_RE = Regex("""(?i)color\s*:\s*([^;"]+)""")
    private val BLOCK_OPEN_RE = Regex("""(?i)<\s*(p|div|li|tr|td|th|h[1-6])(\s[^>]*)?>""")
    private val BLOCK_CLOSE_RE = Regex("""(?i)<\s*/\s*(p|div|li|tr|td|th|h[1-6])\s*>""")
    /** Qualquer tag HTML real (exige letra/número depois de "<" — "a < b" não casa). */
    private val TAG_RE = Regex("""</?[A-Za-z][A-Za-z0-9]*(\s[^<>]*)?/?>""")

    private fun argbToCss(argb: Long): String {
        val a = ((argb shr 24) and 0xFF) / 255.0
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return if (a >= 0.999) "rgb($r, $g, $b)" else "rgba($r, $g, $b, ${"%.3f".format(a)})"
    }

    /** Converte "rgb(...)", "rgba(...)", "#rgb", "#rrggbb" em (r,g,b). */
    internal fun parseColor(raw: String): Triple<Int, Int, Int>? {
        val v = raw.trim().removeSuffix(";").trim()
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            return when (hex.length) {
                3 -> Triple(
                    hex[0].digitToInt(16) * 17,
                    hex[1].digitToInt(16) * 17,
                    hex[2].digitToInt(16) * 17,
                )
                6 -> Triple(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                )
                else -> null
            }
        }
        val m = Regex("""(?i)rgba?\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(v) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** Luminância relativa WCAG (0..1) de um RGB. */
    internal fun relativeLuminance(rgb: Triple<Int, Int, Int>): Double {
        fun lin(v: Int): Double {
            val d = v / 255.0
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(rgb.first) + 0.7152 * lin(rgb.second) + 0.0722 * lin(rgb.third)
    }

    /** Entidades HTML: nomeadas comuns + numéricas + Latin-1 (o S-34 usa &eacute; etc.). */
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "middot" to "·", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘",
        "rsquo" to "’", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™",
    )

    /** Nomes das entidades Latin-1 na ordem dos códigos 160..255 (HTML 4). */
    private val LATIN1_NAMES = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 " +
            "acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
            "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
            "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
            "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
            "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(" ")

    private val LATIN1_ENTITIES: Map<String, String> = buildMap {
        LATIN1_NAMES.forEachIndexed { index, name ->
            val code = 160 + index
            put(name, String(Character.toChars(code)))
        }
    }

    private fun decodeEntities(text: String): String {
        var out = text
        // numéricas: &#233; &#xE9;
        out = NUM_ENTITY_RE.replace(out) { m ->
            val code = if (m.groupValues[1].isNotEmpty()) {
                m.groupValues[1].toInt()
            } else {
                m.groupValues[2].toInt(16)
            }
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        // nomeadas (comuns + Latin-1)
        out = Regex("""&([a-zA-Z]+);""").replace(out) { m ->
            val name = m.groupValues[1].lowercase()
            NAMED_ENTITIES[name] ?: LATIN1_ENTITIES[name] ?: m.value
        }
        return out
    }

    private val NUM_ENTITY_RE = Regex("""&#(\d+);|&#x([0-9a-fA-F]+);""")
}
