package com.bettertalker.app.data.util

/**
 * Fase A — normalização única da colagem antes do reconhecimento V&M.
 *
 * Textos copiados de PDF/Word chegam com NBSP, bullets e minutos órfãos na
 * linha seguinte. Sem normalizar, as regexes do detector falham por detalhes
 * invisíveis (ex.: `\s` do Java não cobre `\u00A0`).
 *
 * Puro/testável. Não altera a indentação além dos bullets (o `OutlineParser`
 * continua recebendo o texto com sua estrutura).
 */
object PasteTextNormalizer {

    /** Linha composta apenas por "(N min)" / "N minutos". */
    private val ORPHAN_MINUTES_RE =
        Regex("""^\s*\(?\s*\d{1,3}\s*(?:min|minutos?)\.?\s*\)?\s*$""", RegexOption.IGNORE_CASE)

    /** A linha anterior já termina com minutos? (não juntar de novo) */
    private val ENDS_WITH_MINUTES_RE =
        Regex("""\d{1,3}\s*(?:min|minutos?)\.?\s*\)?\s*$""", RegexOption.IGNORE_CASE)

    /** Bullets comuns de PDF/Word no início da linha. */
    private val BULLET_RE = Regex("""^\s*[•◦▪‣·]\s*""")

    /** Travessões tipográficos → hífen simples. */
    private val DASHES = charArrayOf('\u2013', '\u2014', '\u2212')

    /**
     * Normaliza a colagem para o detector:
     * 1. NBSP → espaço;
     * 2. travessões tipográficos → `-`;
     * 3. remove bullets no início das linhas;
     * 4. colapsa espaços/tabs internos (preserva quebras);
     * 5. junta "(N min)" órfão à linha anterior.
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return raw.trim()

        val cleaned = StringBuilder(raw.length)
        for (ch in raw) {
            cleaned.append(
                when (ch) {
                    '\u00A0', '\u202F' -> ' '
                    in DASHES -> '-'
                    else -> ch
                }
            )
        }

        val lines = cleaned.toString()
            .split('\n')
            .map { line ->
                // Preserva a INDENTAÇÃO (o OutlineParser usa recuo para corpo);
                // colapsa apenas espaços/tabs internos. Bullet remove o prefixo.
                val withoutBullet = BULLET_RE.replace(line, "")
                val indent = withoutBullet.takeWhile { it == ' ' || it == '\t' }
                val rest = withoutBullet.substring(indent.length)
                    .replace(Regex("[ \\t]+"), " ")
                    .trimEnd()
                indent + rest
            }

        val joined = mutableListOf<String>()
        for (line in lines) {
            if (line.isNotBlank() && ORPHAN_MINUTES_RE.matches(line) &&
                joined.isNotEmpty() && joined.last().isNotBlank() &&
                !ENDS_WITH_MINUTES_RE.containsMatchIn(joined.last())
            ) {
                joined[joined.size - 1] = (joined.last().trimEnd() + " " + line.trim()).trim()
            } else {
                joined += line
            }
        }
        return joined.joinToString("\n").trim()
    }
}
