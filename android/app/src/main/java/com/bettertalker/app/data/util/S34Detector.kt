package com.bettertalker.app.data.util

/**
 * Fase 19-B.1 — detecção determinística de esboço S-34.
 *
 * Domínio puro: texto entra, classificação sai. Sem LLM, sem rede, sem
 * retrieval, sem Firebase, sem IO. Não é chamado por ninguém ainda
 * (integração decidida na próxima fatia); existe isoladamente e testado.
 *
 * Estratégia (multi-sinal, sem palavra mágica única):
 * - `S-34` como marcador obrigatório — evidência: OutlineParser.NOISE_RE
 *   trata `S-\d+` como cabeçalho de esboço para descartar;
 * - + pelo menos 2 sinais estruturais entre: seções temporizadas
 *   (convenção `(N min)`, mesma de OutlineParser.MIN_RE), pontos
 *   numerados, referências bíblicas (RefDetector.detectBible),
 *   referências de publicação (RefDetector.detect) e bloco de objetivo.
 * O marcador sozinho não basta (uma menção passageira não é um esboço) e
 * refs sozinhas sem marcador também não (não confundir S-34 com publicação,
 * Bíblia ou BE/TH — o marcador é o portão).
 */
object S34Detector {

    /** Piso anti-fragmento: abaixo disso nem um cabeçalho + 2 sinais cabem
     * de forma confiável. Heurística documentada, não verdade de domínio. */
    const val MIN_S34_CHARS = 200

    /** S-34, S34, S 34, s-34. */
    val MARKER_RE = Regex("""\bS[-\s]?34\b""", RegexOption.IGNORE_CASE)

    /**
     * T1 (Bug #9) — marcador no NOME do arquivo (ex.: `s34-35.docx`,
     * `S-34_T_035.jwpub`). S-34 reais podem não trazer o literal no texto;
     * o nome do arquivo é evidência legítima no caminho de import.
     * Lookarounds (não `\b`): `_` é word char e quebraria `S-34_T…`.
     */
    val FILE_MARKER_RE = Regex("""(?i)(?<![A-Za-z0-9])S[-_ ]?34(?![0-9])""")

    fun hasFileMarker(fileName: String?): Boolean =
        !fileName.isNullOrBlank() &&
            FILE_MARKER_RE.containsMatchIn(fileName.substringBeforeLast('.'))

    /** Pontos numerados ("1.", "2)") no início da linha. */
    val NUMBERED_RE = Regex("""(?m)^\s*\d{1,2}[.)]\s+\S""")

    /** Bloco de objetivo ("Objetivo:"). Sinal de apoio — nunca decide sozinho. */
    val OBJECTIVE_RE = Regex("""(?m)^\s*objetivo\s*:""", RegexOption.IGNORE_CASE)

    enum class S34Signal {
        TIMED_SECTIONS,
        NUMBERED_POINTS,
        BIBLE_REFS,
        PUB_REFS,
        OBJECTIVE
    }

    fun hasMarker(text: String): Boolean = MARKER_RE.containsMatchIn(text)

    /** Sinais encontrados (sem portão de tamanho/marcador — ver [isS34]). */
    fun signals(text: String): Set<S34Signal> {
        val out = mutableSetOf<S34Signal>()
        if (OutlineParser.MIN_RE.findAll(text).count() >= 2) out += S34Signal.TIMED_SECTIONS
        if (NUMBERED_RE.findAll(text).count() >= 2) out += S34Signal.NUMBERED_POINTS
        if (RefDetector.detectBible(text).isNotEmpty()) out += S34Signal.BIBLE_REFS
        if (RefDetector.detect(text).isNotEmpty()) out += S34Signal.PUB_REFS
        if (OBJECTIVE_RE.containsMatchIn(text)) out += S34Signal.OBJECTIVE
        return out
    }

    /** Verdadeiro somente para esboço S-34 completo o bastante para analisar.
     * [fileName] é sinal alternativo ao literal (T1/Bug #9): S-34 reais podem
     * ser nomeados `s34*` sem conter "S-34" no texto. */
    fun isS34(text: String, fileName: String? = null): Boolean {
        if (text.length < MIN_S34_CHARS) return false
        if (!hasMarker(text) && !hasFileMarker(fileName)) return false
        return signals(text).size >= 2
    }
}
