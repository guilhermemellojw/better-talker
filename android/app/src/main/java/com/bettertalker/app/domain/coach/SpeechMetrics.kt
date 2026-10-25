package com.bettertalker.app.domain.coach

import com.bettertalker.app.data.util.STOPWORDS_PT
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.domain.planning.SpeechPacing
import kotlin.math.roundToInt

/**
 * Métricas locais do ensaio (T3-A). Nada aqui grava ou envia áudio: a
 * transcrição é a única entrada e vive em memória.
 *
 * @param speechDurationSec tempo efetivo de escuta (Listening), base do wpm.
 * @param sessionDurationSec tempo total da sessão (inclui pausas/interrupções).
 * @param spokenWords palavras da transcrição.
 * @param wpm palavras por minuto (usa [speechDurationSec]).
 * @param targetWpm ritmo de referência do app ([SpeechPacing]).
 * @param coveragePercent recall de palavras de conteúdo do texto (0–100).
 * @param missingTerms palavras-chave do texto ausentes na fala (máx. 5).
 * @param fillerCounts muletas encontradas, com rótulo legível.
 */
data class SpeechMetrics(
    val speechDurationSec: Int,
    val sessionDurationSec: Int,
    val spokenWords: Int,
    val wpm: Int,
    val targetWpm: Int = SpeechPacing.WORDS_PER_MINUTE,
    val coveragePercent: Int,
    val missingTerms: List<String> = emptyList(),
    val fillerCounts: Map<String, Int> = emptyMap(),
) {
    val fillerTotal: Int get() = fillerCounts.values.sum()
}

/** Muletas monitoradas (chave normalizada -> rótulo exibido). */
private val FILLER_LABELS = linkedMapOf(
    "ne" to "né",
    "tipo" to "tipo",
    "entao" to "então",
    "aqui" to "aqui",
    "certo" to "certo",
    "ok" to "ok",
    "eee" to "eee",
    "hum" to "hum",
    "sabe" to "sabe",
)

/** Conta palavras com letra/dígito; pontuação sozinha não conta. Puro. */
fun countSpokenWords(raw: String): Int =
    raw.split(Regex("\\s+")).count { token -> token.any(Char::isLetterOrDigit) }

/** Palavras de conteúdo: normalizadas, sem stopwords e com 3+ caracteres. */
private fun contentTokens(raw: String): List<String> =
    normalizeText(raw)
        .split(' ')
        .filter { it.length >= 3 && it !in STOPWORDS_PT && it.any(Char::isLetterOrDigit) }

/**
 * Calcula as métricas do ensaio. A cobertura é um recall de palavras de
 * conteúdo (não uma nota): serve para apontar o que não apareceu, nunca para
 * julgar dicção. Texto-alvo vazio = cobertura 100 (não penaliza).
 *
 * Puro/testável.
 */
fun computeSpeechMetrics(
    transcript: String,
    targetText: String,
    speechDurationSec: Int,
    sessionDurationSec: Int = speechDurationSec,
    targetWpm: Int = SpeechPacing.WORDS_PER_MINUTE,
): SpeechMetrics {
    val speechSeconds = speechDurationSec.coerceAtLeast(0)
    val sessionSeconds = sessionDurationSec.coerceAtLeast(speechSeconds)
    val words = countSpokenWords(transcript)
    // Pausas não entram no ritmo: o wpm usa só o tempo de escuta.
    val wpm = if (speechSeconds > 0) (words * 60.0 / speechSeconds).roundToInt() else 0

    // Mantém a grafia original para o feedback ("Jerusalém", não "jerusalem").
    val targetByNorm = linkedMapOf<String, String>()
    Regex("""[\p{L}\p{N}]+""").findAll(targetText).forEach { match ->
        val norm = normalizeText(match.value)
        if (norm.length >= 3 && norm !in STOPWORDS_PT) {
            targetByNorm.putIfAbsent(norm, match.value)
        }
    }
    val spokenSet = contentTokens(transcript).toSet()
    val matched = targetByNorm.keys.count { it in spokenSet }
    val coverage = if (targetByNorm.isEmpty()) {
        100
    } else {
        (100.0 * matched / targetByNorm.size).roundToInt().coerceIn(0, 100)
    }
    val missing = targetByNorm
        .filterKeys { it !in spokenSet }
        .values
        .distinct()
        .take(5)

    val normalizedTokens = normalizeText(transcript).split(' ')
    val fillers = linkedMapOf<String, Int>()
    for ((norm, label) in FILLER_LABELS) {
        val count = normalizedTokens.count { it == norm }
        if (count > 0) fillers[label] = count
    }

    return SpeechMetrics(
        speechDurationSec = speechSeconds,
        sessionDurationSec = sessionSeconds,
        spokenWords = words,
        wpm = wpm,
        targetWpm = targetWpm,
        coveragePercent = coverage,
        missingTerms = missing,
        fillerCounts = fillers,
    )
}
