package com.bettertalker.app.data.util

/**
 * F5b (Modo C) — "isto parece texto de publicação JW.org?".
 *
 * Heurística conservadora e determinística (sem LLM/rede/IO): cada sinal
 * fraco soma 1; [PublicationSignal.matched] exige score >= 2. NUNCA bloqueia
 * nada — o comando explícito do usuário confirma (o handler prossegue mesmo
 * com matched=false e só anota os [PublicationSignal.hints] no anúncio).
 * Puro/testável.
 */
data class PublicationSignal(
    val matched: Boolean,
    val score: Int,
    /** Sinais detectados, ex.: "refs de publicação (4)", "marcas [Leia …] (3)". */
    val hints: List<String>,
)

object PublicationDetector {

    /** Piso do score para marcar como publicação. */
    const val MATCH_THRESHOLD = 2

    /**
     * Marcas de leitura do esboço/publicação ("[Leia João 3:16.]", "[Leia
     * Salmo 46:1, 2.]"). Sobre texto normalizado (sem `[`/`]`).
     */
    private val LEIA_RE = Regex("\\bleia\\b")

    /** Termos característicos de publicações (texto normalizado). */
    private val JW_TERMS_RE = Regex("\\b(sentinela|despertai|testemunhas de jeova|jeova deus)\\b")

    /** Proporção de parágrafos terminando em "?" para o estilo pergunta-resposta. */
    private const val QUESTION_RATIO = 0.4

    /**
     * Sinais (cada um soma 1):
     * 1. >= 3 referências de publicação (RefDetector.detect);
     * 2. >= 2 marcas "[Leia …]";
     * 3. >= 3 termos de publicações JW (Sentinela/Despertai!/…);
     * 4. estilo pergunta-resposta (> 40% dos parágrafos terminam em "?").
     *
     * matched = score >= [MATCH_THRESHOLD]. Puro/testável.
     */
    fun looksLikePublication(text: String): PublicationSignal {
        val normalized = normalizeText(text)
        var score = 0
        val hints = mutableListOf<String>()

        val pubRefs = RefDetector.detect(text)
        if (pubRefs.size >= 3) {
            score++
            hints += "refs de publicação (${pubRefs.size})"
        }

        val leia = LEIA_RE.findAll(normalized).count()
        if (leia >= 2) {
            score++
            hints += "marcas [Leia …] ($leia)"
        }

        val jw = JW_TERMS_RE.findAll(normalized).count()
        if (jw >= 3) {
            score++
            hints += "termos de publicações JW ($jw)"
        }

        // Parágrafos: sobre o texto ORIGINAL (normalizeText remove "?").
        val paragraphs = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.size >= 3) {
            val questions = paragraphs.count { it.endsWith("?") }
            val ratio = questions.toDouble() / paragraphs.size
            if (ratio > QUESTION_RATIO) {
                score++
                hints += "estilo pergunta-resposta (${(ratio * 100).toInt()}%)"
            }
        }

        return PublicationSignal(
            matched = score >= MATCH_THRESHOLD,
            score = score,
            hints = hints,
        )
    }
}
