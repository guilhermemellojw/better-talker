package com.bettertalker.app.data.work

import com.bettertalker.app.data.db.PassageEntity
import com.bettertalker.app.data.util.buildRef
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.data.util.splitWithSectionsAndParagraphs

/**
 * Constrói PassageEntity a partir do texto bruto de uma publicação.
 *
 * Puro, determinístico, testável em JVM.
 *
 * Preserva o comportamento do IndexPublicationWorker:
 * - cap de [maxSentences] frases
 * - filtro de frases com normalizado <= [minNormalizedLength]
 * - cap de texto/normalizado em [maxTextLength] chars
 * - `ord` contado DEPOIS do filtro (sequencial de zero)
 *
 * @param attachmentId id do attachment pai
 * @param symbol símbolo editorial (via detectSymbol)
 * @param raw texto bruto extraído
 * @param trainingCategory categoria be/th, se aplicável (uniforme; o worker
 *   refina por frase para o trilho training — ver IndexPublicationWorker)
 * @param maxSentences cap de frases (default 2500, igual ao worker)
 * @param minNormalizedLength comprimento mínimo do normalizado (default 5)
 * @param maxTextLength cap de texto/normalizado (default 500)
 */
/** Piso do teto de frases (publicações pequenas). */
internal const val MIN_PASSAGE_CAP = 2500

/** Teto do teto de frases (Estudo Perspicaz unificado = 12,43M chars → ~124k frases). */
internal const val MAX_PASSAGE_CAP = 300_000

/**
 * T1b — teto de frases proporcional ao texto (≈1 frase a cada 40 chars):
 * publicações grandes têm muito mais que 2500 frases e eram truncadas.
 * Puro/testável.
 */
internal fun passageCapFor(textLength: Int): Int =
    (textLength / 40).coerceIn(MIN_PASSAGE_CAP, MAX_PASSAGE_CAP)

internal fun buildPassages(
    attachmentId: String,
    symbol: String,
    raw: String,
    trainingCategory: String?,
    maxSentences: Int = 2500,
    minNormalizedLength: Int = 5,
    maxTextLength: Int = 500,
): List<PassageEntity> {
    val triples = splitWithSectionsAndParagraphs(raw).take(maxSentences)
    val result = mutableListOf<PassageEntity>()
    var ord = 0
    for ((s, section, paragraph) in triples) {
        val norm = normalizeText(s)
        if (norm.length <= minNormalizedLength) continue
        val text = s.replace(Regex("\\s+"), " ").trim().take(maxTextLength)
        result.add(
            PassageEntity(
                id = "$attachmentId-p$ord",
                attachmentId = attachmentId,
                text = text,
                normalized = norm.take(maxTextLength),
                section = section,
                ref = buildRef(symbol, section, ord + 1),
                // T4b: parágrafo REAL do JWPUB (marcador §N do extrator).
                paragraph = paragraph,
                ord = ord,
                trainingCategory = trainingCategory,
            )
        )
        ord++
    }
    return result
}
