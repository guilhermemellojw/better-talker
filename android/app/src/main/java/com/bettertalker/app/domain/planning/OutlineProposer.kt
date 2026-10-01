package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.planning.retrieval.BibleIndex
import com.bettertalker.app.domain.planning.retrieval.MethodIndex
import com.bettertalker.app.domain.planning.retrieval.PublicationIndex
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Gera até 3 propostas de esboço (uma por ângulo) a partir de um tema.
 *
 * Orquestra retrieval (uma vez) + geração por ângulo + validação.
 * Puro e testável: depende apenas de interfaces ([BibleIndex], [PublicationIndex],
 * [MethodIndex], [OutlineGenerator]).
 *
 * @param idProvider gera o id de cada proposta (default: UUID aleatório).
 */
class OutlineProposer(
    private val bibleIndex: BibleIndex,
    private val publicationIndex: PublicationIndex,
    private val methodIndex: MethodIndex,
    private val generator: OutlineGenerator,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Propõe esboços para [theme], na ordem de ângulos DOCTRINAL, PRACTICAL, NARRATIVE.
     *
     * Retrieval é feito uma única vez e o resultado é repassado a cada geração.
     * Para cada ângulo: gera, valida via [OutlineProposalValidator] e mantém
     * apenas propostas válidas. Geração null, proposta inválida ou exceção genérica
     * do gerador para um ângulo apenas pula aquele ângulo (não propaga, não derruba
     * os demais). [CancellationException] é propagada (structured concurrency
     * preservada): cancelamento nunca é engolido. Execução estritamente
     * sequencial, sem paralelismo.
     *
     * @return lista com 0 a 3 propostas válidas, na ordem dos ângulos.
     */
    suspend fun propose(
        theme: String,
        totalMinutes: Int,
        audience: Audience,
    ): List<OutlineProposal> {
        val bibleRefs = bibleIndex.findByTheme(theme, BIBLE_LIMIT)
        val publicationRefs = publicationIndex.findByTheme(theme, PUBLICATION_LIMIT)
        val methodPrinciples = methodIndex.findPrinciples(theme, METHOD_LIMIT)
        val proposals = mutableListOf<OutlineProposal>()
        for (angle in ANGLES_IN_ORDER) {
            val request = OutlineGenerationRequest(
                id = idProvider(),
                theme = theme,
                totalMinutes = totalMinutes,
                audience = audience,
                angle = angle,
                bibleRefs = bibleRefs,
                publicationRefs = publicationRefs,
                methodPrinciples = methodPrinciples,
            )
            val proposal = try {
                generator.generate(request)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            } ?: continue
            if (OutlineProposalValidator.validate(proposal) is ValidationResult.Valid) {
                proposals.add(proposal)
            }
        }
        return proposals
    }

    private companion object {
        val ANGLES_IN_ORDER = listOf(
            OutlineAngle.DOCTRINAL,
            OutlineAngle.PRACTICAL,
            OutlineAngle.NARRATIVE,
        )
        const val BIBLE_LIMIT = 20
        const val PUBLICATION_LIMIT = 10
        const val METHOD_LIMIT = 5
    }
}
