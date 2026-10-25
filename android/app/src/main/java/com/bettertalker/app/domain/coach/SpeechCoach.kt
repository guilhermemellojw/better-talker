package com.bettertalker.app.domain.coach

import kotlin.math.abs

/**
 * Feedback local do ensaio (T3-A). Quatro blocos, um exercício, sem nota e
 * sem julgamento. Puro/testável.
 */
data class CoachFeedback(
    val good: List<String>,
    val adjust: List<String>,
    val pattern: String,
    val exercise: String,
)

/** "1 min 20 s" / "20 s" — rótulo humano de duração. Puro. */
fun formatCoachDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s < 60) "${s} s" else "${s / 60} min ${s % 60} s"
}

private fun sectionRef(sectionTitles: List<String>): String {
    val title = sectionTitles.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?: sectionTitles.firstOrNull()?.takeIf { it.isNotBlank() }
    return if (title != null) "“$title”" else "do trecho atual"
}

/**
 * Gera o feedback a partir das métricas. A v1 é global (sem dados por
 * seção); [sectionTitles] só escolhe um trecho para o exercício.
 */
fun buildCoachFeedback(
    metrics: SpeechMetrics,
    targetMinutes: Int,
    sectionTitles: List<String> = emptyList(),
): CoachFeedback {
    val target = metrics.targetWpm
    val hasSpeech = metrics.spokenWords > 0
    val tooFast = hasSpeech && metrics.wpm > target + 15
    val tooSlow = hasSpeech && metrics.wpm > 0 && metrics.wpm < target - 15
    val lowCoverage = hasSpeech && metrics.coveragePercent < 80
    val manyFillers = hasSpeech && metrics.fillerTotal > 2
    val targetSec = targetMinutes.coerceAtLeast(0) * 60
    val tooLong = hasSpeech && targetSec > 0 && metrics.durationSec > targetSec + 30
    val tooShort = hasSpeech && targetSec > 0 && metrics.durationSec < targetSec - 30
    val topFiller = metrics.fillerCounts.maxByOrNull { it.value }
    val ref = sectionRef(sectionTitles)

    val good = buildList {
        if (hasSpeech && abs(metrics.wpm - target) <= 15) {
            add("Ritmo dentro da faixa (~${metrics.wpm} palavras/min).")
        }
        if (hasSpeech && metrics.coveragePercent >= 80) {
            add("Boa cobertura do texto (${metrics.coveragePercent}%).")
        }
        if (hasSpeech && targetSec > 0 && abs(metrics.durationSec - targetSec) <= 30) {
            add("Tempo próximo da meta (${formatCoachDuration(metrics.durationSec)}).")
        }
        if (hasSpeech && metrics.fillerTotal <= 2) {
            add("Poucas muletas.")
        }
        if (isEmpty()) {
            add(
                if (hasSpeech) "Você concluiu o ensaio e já tem dados para ajustar."
                else "O timer continuou rodando mesmo sem transcrição."
            )
        }
    }

    val adjust = buildList {
        if (!hasSpeech) {
            add("Não recebi fala suficiente para analisar. Verifique o microfone e tente de novo.")
        }
        if (tooFast) {
            add("Você está acelerando; tente pausar entre os pontos.")
        }
        if (tooSlow) {
            add("Você está devagar; reduza as pausas ou ensaie o trecho.")
        }
        if (lowCoverage) {
            val missing = metrics.missingTerms.take(3).joinToString(", ")
            add(
                "Faltou cobrir ${100 - metrics.coveragePercent}% do texto" +
                    if (missing.isNotBlank()) "; verifique: $missing." else "."
            )
        }
        if (manyFillers && topFiller != null) {
            add(
                "Você repetiu “${topFiller.key}” ${topFiller.value} vezes; " +
                    "tente substituir por uma pausa."
            )
        }
        if (tooLong) {
            add(
                "O ensaio passou da meta em " +
                    "${formatCoachDuration(metrics.durationSec - targetSec)}; corte repetições."
            )
        }
        if (tooShort) {
            add(
                "O ensaio ficou ${formatCoachDuration(targetSec - metrics.durationSec)} " +
                    "abaixo da meta; desenvolva melhor o ponto."
            )
        }
    }

    val pattern = when {
        !hasSpeech -> "O reconhecimento não retornou texto; o timer foi preservado."
        tooFast -> "No geral, o ritmo subiu além do alvo."
        tooSlow -> "No geral, o ritmo ficou abaixo do alvo."
        lowCoverage -> "No geral, partes do texto não apareceram na fala."
        manyFillers -> "As muletas aparecem mais nos trechos improvisados."
        else -> "No geral, ritmo e cobertura ficaram consistentes."
    }

    val exercise = when {
        !hasSpeech -> "Verifique o microfone e grave 30 segundos do início."
        tooFast -> "Grave 1 min de $ref e faça uma pausa curta a cada frase."
        tooSlow -> "Leia $ref em voz alta uma vez, sem pausas longas."
        lowCoverage -> "Releia o trecho e diga com suas palavras: " +
            "${metrics.missingTerms.firstOrNull() ?: "a ideia central"}."
        manyFillers && topFiller != null ->
            "Grave 1 min e troque cada “${topFiller.key}” por uma pausa de 1 segundo."
        else -> "Grave 1 min de $ref e mantenha o mesmo ritmo."
    }

    return CoachFeedback(
        good = good,
        adjust = adjust,
        pattern = pattern,
        exercise = exercise,
    )
}

/** Texto corrido para o TTS do feedback (sem markdown). Puro/testável. */
fun coachFeedbackSpeech(feedback: CoachFeedback): String = buildString {
    append("O que foi bem. ")
    append(feedback.good.joinToString(" "))
    if (feedback.adjust.isNotEmpty()) {
        append(" O que ajustar. ")
        append(feedback.adjust.joinToString(" "))
    }
    append(" Padrão observado. ")
    append(feedback.pattern)
    append(" Exercício. ")
    append(feedback.exercise)
}
