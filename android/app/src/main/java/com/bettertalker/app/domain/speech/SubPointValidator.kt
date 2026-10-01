package com.bettertalker.app.domain.speech

/**
 * Resultado da validação de uma lista de [SubPoint].
 */
sealed class SubPointValidationResult {
    /** Lista sem erros. */
    object Valid : SubPointValidationResult()

    /** Lista com erros, na ordem de [SubPointValidationError]. */
    data class Invalid(val errors: List<SubPointValidationError>) : SubPointValidationResult()
}

/** Erros possíveis na validação de sub-pontos. */
enum class SubPointValidationError {
    BLANK_OUTLINE_TEXT,
    BLANK_SECTION_ID,
    NEGATIVE_ORDER,
    NON_CONTIGUOUS_ORDER,
}

/**
 * Valida lista de [SubPoint] de uma mesma seção (ordenada por [SubPoint.order]).
 *
 * Stateless e thread-safe. Acumula todos os erros aplicáveis na ordem do enum.
 * Convenção de order: 0-based contíguo (0, 1, 2, ...).
 */
object SubPointValidator {

    /**
     * Valida [points], retornando todos os erros na ordem do enum.
     */
    fun validate(points: List<SubPoint>): SubPointValidationResult {
        val errors = mutableListOf<SubPointValidationError>()
        if (points.any { it.outlineText.isBlank() }) {
            errors.add(SubPointValidationError.BLANK_OUTLINE_TEXT)
        }
        if (points.any { it.sectionId.isBlank() }) {
            errors.add(SubPointValidationError.BLANK_SECTION_ID)
        }
        if (points.any { it.order < 0 }) {
            errors.add(SubPointValidationError.NEGATIVE_ORDER)
        } else {
            val expected = points.indices.toList()
            if (points.map { it.order } != expected) {
                errors.add(SubPointValidationError.NON_CONTIGUOUS_ORDER)
            }
        }
        return if (errors.isEmpty()) {
            SubPointValidationResult.Valid
        } else {
            SubPointValidationResult.Invalid(errors)
        }
    }
}
