package com.bettertalker.app.domain.planning

/**
 * Fase A — ritmo de fala único do app.
 *
 * Antes o número `130` estava duplicado em `OratoryGeneration`,
 * `DossierPromptBuilder`, `SectionGeneratorImpl` e `WordCount`. Qualquer ajuste
 * em um deles deixava os outros divergentes (orçamento de tokens, meta de
 * palavras e estimativa do editor). Agora todos consomem esta fonte.
 *
 * Puro/testável.
 */
object SpeechPacing {
    /** Ritmo médio de fala em português (palavras por minuto). */
    const val WORDS_PER_MINUTE = 130

    /** Meta de palavras para uma fala de [minutes] minutos (mínimo 1 min). */
    fun targetWords(minutes: Int): Int =
        minutes.coerceAtLeast(1) * WORDS_PER_MINUTE

    /**
     * Meta de réplicas para uma demonstração de [minutes] minutos.
     * ~2,5 réplicas/min: 4 min ≈ 10 réplicas (faixa prática de 8–12).
     */
    fun targetTurns(minutes: Int): Int =
        (minutes.coerceAtLeast(1) * 2.5).toInt().coerceAtLeast(2)
}
