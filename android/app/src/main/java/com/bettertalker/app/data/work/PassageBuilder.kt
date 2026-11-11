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
 * - `text` guarda o trecho INTEGRAL; [maxTextLength] limita só o
 *   `normalized` usado na busca
 * - `ord` contado DEPOIS do filtro (sequencial de zero)
 *
 * @param attachmentId id do attachment pai
 * @param symbol símbolo editorial (via detectSymbol)
 * @param raw texto bruto extraído
 * @param trainingCategory categoria be/th, se aplicável (uniforme; o worker
 *   refina por frase para o trilho training — ver IndexPublicationWorker)
 * @param maxSentences cap de frases (default 2500, igual ao worker)
 * @param minNormalizedLength comprimento mínimo do normalizado (default 5)
 * @param maxTextLength cap do normalizado (default 500); o texto visível
 *   ao usuário NÃO é truncado
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
    // §N do JWPUB vira um chunk com o parágrafo inteiro; demais textos
    // continuam divididos por frase.
    val chunks = splitWithSectionsAndParagraphs(raw, keepParagraphs = true)
        .take(maxSentences)
    val result = mutableListOf<PassageEntity>()
    var ord = 0
    for (chunk in chunks) {
        val s = chunk.text
        val norm = normalizeText(s)
        if (norm.length <= minNormalizedLength) continue
        // 6.0a/P0: o índice guarda a faixa inteira. O reader precisa do
        // texto completo para o usuário conferir o que o Copilot leu; só o
        // `normalized` é limitado (busca/índice invertido).
        val text = s.replace(Regex("\\s+"), " ").trim()
        result.add(
            PassageEntity(
                id = "$attachmentId-p$ord",
                attachmentId = attachmentId,
                text = text,
                normalized = norm.take(maxTextLength),
                section = chunk.section,
                ref = buildRef(symbol, chunk.section, ord + 1),
                // T4b: parágrafo REAL do JWPUB (marcador §N do extrator).
                paragraph = chunk.paragraph,
                // RC1-pages: página estimada do artigo (JWPUB); null fora dele.
                page = chunk.page,
                ord = ord,
                trainingCategory = trainingCategory,
            )
        )
        ord++
    }
    return result
}
