package com.bettertalker.app.data.util

/**
 * #26-fix (higiene de texto): o editor salva HTML no campo markdown e as
 * respostas do Copilot trazem marcações próprias (`〈sugestão〉`, `**`, `#`).
 * As prévias da Home engoliam tudo isso cru. Esta sanitização devolve só o
 * texto puro para EXIBIÇÃO — o texto original continua íntegro no banco.
 *
 * Puro/testável: sem Android (o decoder cobre as entidades que o editor
 * gera + numéricas; `android.text.Html` não roda em teste JVM).
 */

/** Entidades nomeadas comuns (editor WYSIWYG + prosa pt-BR). */
private val HTML_ENTITIES = mapOf(
    "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
    "quot" to "\"", "apos" to "'",
    "aacute" to "á", "acirc" to "â", "agrave" to "à", "atilde" to "ã", "auml" to "ä",
    "ccedil" to "ç",
    "eacute" to "é", "ecirc" to "ê", "egrave" to "è",
    "iacute" to "í", "icirc" to "î",
    "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ",
    "uacute" to "ú", "ucirc" to "û", "uuml" to "ü",
    "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã",
    "Ccedil" to "Ç",
    "Eacute" to "É", "Ecirc" to "Ê",
    "Iacute" to "Í",
    "Oacute" to "Ó", "Otilde" to "Õ",
    "Uacute" to "Ú",
    "quest" to "?", "period" to ".", "comma" to ",", "colon" to ":",
    "semi" to ";", "excl" to "!", "hellip" to "…", "mdash" to "—",
    "ndash" to "–", "laquo" to "«", "raquo" to "»",
)

/** Decodifica entidades nomeadas + numéricas (`&#233;` / `&#xE9;`). Puro. */
internal fun decodeHtmlEntities(text: String): String =
    Regex("&(#?[a-zA-Z0-9]+);").replace(text) { m ->
        val key = m.groupValues[1]
        if (key.startsWith("#")) {
            val code = key.drop(1).let {
                if (it.startsWith("x") || it.startsWith("X")) it.drop(1).toIntOrNull(16)
                else it.toIntOrNull()
            }
            code?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
        } else {
            HTML_ENTITIES[key] ?: m.value
        }
    }

/**
 * #26-fix: prévia limpa — remove tags HTML, decodifica entidades, tira
 * marcações markdown (`* _ # ` >`) e marcadores do Copilot
 * (`〈sugestão〉…〈/sugestão〉`, `〈decisão〉…`, `<texto-do-card>`), colapsa
 * espaços e trunca com `…`. Puro/testável.
 */
fun String.toCleanPreview(maxLength: Int = 140): String {
    var text = replace(Regex("<[^>]*>"), " ")
    text = decodeHtmlEntities(text)
    text = text.replace(Regex("[*_#`>]"), "")
    text = text
        .replace("〈sugestão〉", "").replace("〈/sugestão〉", "")
        .replace("〈decisão〉", "").replace("〈/decisão〉", "")
        .replace("<texto-do-card>", "").replace("</texto-do-card>", "")
    text = text.replace(Regex("\\s+"), " ").trim()
    return if (text.length <= maxLength) text else text.take(maxLength).trimEnd() + "…"
}
