package com.bettertalker.app.domain.planning

/**
 * Resultado da validação de um [OutlineProposal].
 */
sealed class ValidationResult {
    /** Proposta sem erros. */
    object Valid : ValidationResult()

    /** Proposta com erros, na ordem documentada em [OutlineProposalValidator]. */
    data class Invalid(val errors: List<ValidationError>) : ValidationResult()
}

/** Erros possíveis na validação de um [OutlineProposal]. */
enum class ValidationError {
    BLANK_TITLE,
    BLANK_SUMMARY,
    NON_POSITIVE_TOTAL_MINUTES,
    NO_SECTIONS,
    SECTION_BLANK_TITLE,
    SECTION_NON_POSITIVE_MINUTES,
    SECTION_BLANK_MAIN_IDEA,
    DUPLICATE_SECTION_TITLES,
    NO_SECTION_WITH_BIBLE_REFS,
    SECTION_MINUTES_SUM_MISMATCH,
}

/**
 * Valida um [OutlineProposal] de forma pura e determinística.
 *
 * Stateless e thread-safe. Retorna TODOS os erros encontrados, na ordem da spec:
 * BLANK_TITLE, BLANK_SUMMARY, NON_POSITIVE_TOTAL_MINUTES, NO_SECTIONS,
 * SECTION_BLANK_TITLE, SECTION_NON_POSITIVE_MINUTES, SECTION_BLANK_MAIN_IDEA,
 * DUPLICATE_SECTION_TITLES, NO_SECTION_WITH_BIBLE_REFS, SECTION_MINUTES_SUM_MISMATCH.
 *
 * Decisões:
 * - Títulos duplicados comparam após trim, case-insensitive.
 * - Soma de minutos só é erro quando excede o total (sum > totalMinutes);
 *   total maior que a soma é válido (intro/conclusão podem consumir minutos
 *   não alocados a seções específicas).
 * - NO_SECTION_WITH_BIBLE_REFS e SECTION_MINUTES_SUM_MISMATCH só são checados
 *   quando há seções (com lista vazia, NO_SECTIONS já reporta o problema).
 */
object OutlineProposalValidator {

    /**
     * Valida [proposal], acumulando todos os erros na ordem da spec.
     *
     * @param proposal proposta a validar.
     * @return [ValidationResult.Valid] se não houver erros,
     *   ou [ValidationResult.Invalid] com a lista ordenada de erros.
     */
    fun validate(proposal: OutlineProposal): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        if (proposal.title.isBlank()) errors.add(ValidationError.BLANK_TITLE)
        if (proposal.summary.isBlank()) errors.add(ValidationError.BLANK_SUMMARY)
        if (proposal.totalMinutes <= 0) errors.add(ValidationError.NON_POSITIVE_TOTAL_MINUTES)
        if (proposal.sections.isEmpty()) {
            errors.add(ValidationError.NO_SECTIONS)
        } else {
            if (proposal.sections.any { it.title.isBlank() }) {
                errors.add(ValidationError.SECTION_BLANK_TITLE)
            }
            if (proposal.sections.any { it.minutes <= 0 }) {
                errors.add(ValidationError.SECTION_NON_POSITIVE_MINUTES)
            }
            if (proposal.sections.any { it.mainIdea.isBlank() }) {
                errors.add(ValidationError.SECTION_BLANK_MAIN_IDEA)
            }
            val normalizedTitles = proposal.sections.map { it.title.trim().lowercase() }
            if (normalizedTitles.size != normalizedTitles.toSet().size) {
                errors.add(ValidationError.DUPLICATE_SECTION_TITLES)
            }
            if (proposal.sections.none { it.bibleRefs.isNotEmpty() }) {
                errors.add(ValidationError.NO_SECTION_WITH_BIBLE_REFS)
            }
            if (proposal.sections.sumOf { it.minutes } > proposal.totalMinutes) {
                errors.add(ValidationError.SECTION_MINUTES_SUM_MISMATCH)
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}
