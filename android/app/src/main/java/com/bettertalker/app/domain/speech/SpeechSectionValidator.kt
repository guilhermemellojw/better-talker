package com.bettertalker.app.domain.speech

/**
 * Resultado da validação de uma lista de [SpeechSection].
 */
sealed class SectionValidationResult {
    /** Lista sem erros. */
    object Valid : SectionValidationResult()

    /** Lista com erros, na ordem de [SectionValidationError]. */
    data class Invalid(val errors: List<SectionValidationError>) : SectionValidationResult()
}

/** Erros possíveis na validação de seções de discurso. */
enum class SectionValidationError {
    NO_SECTIONS,
    MULTIPLE_INTRO,
    MULTIPLE_CONCLUSION,
    NO_BODY,
    NON_POSITIVE_MINUTES,
    INTRO_NOT_FIRST,
    CONCLUSION_NOT_LAST,
    // NOVOS (3.2.4a): tipos de discurso com 1 BODY único.
    UNEXPECTED_INTRO,
    UNEXPECTED_CONCLUSION,
    WRONG_BODY_COUNT,
}

/**
 * Valida lista de [SpeechSection] (ordenada por [SpeechSection.order]).
 *
 * Stateless e thread-safe. Acumula todos os erros aplicáveis na ordem do enum.
 * Convenção de order: 0-based contíguo — primeira seção tem order 0,
 * última tem order `size - 1`.
 *
 * O [discourseType] (default [DiscourseType.S34_DISCOURSE], compatível com
 * chamadas existentes) seleciona o conjunto de regras: S-34 exige
 * INTRO + N BODY + CONCLUSION; demais tipos exigem exatamente 1 BODY,
 * sem intro nem conclusion.
 */
object SpeechSectionValidator {

    /**
     * Valida [sections], retornando todos os erros na ordem do enum.
     */
    fun validate(
        sections: List<SpeechSection>,
        discourseType: DiscourseType = DiscourseType.S34_DISCOURSE,
    ): SectionValidationResult {
        val errors = mutableListOf<SectionValidationError>()
        if (sections.isEmpty()) {
            return SectionValidationResult.Invalid(listOf(SectionValidationError.NO_SECTIONS))
        }

        when (discourseType) {
            DiscourseType.S34_DISCOURSE -> validateS34(sections, errors)
            DiscourseType.TREASURES_TALK,
            DiscourseType.MINISTRY_PART,
            DiscourseType.AVULSO -> validateSingleBody(sections, errors)
        }

        return if (errors.isEmpty()) {
            SectionValidationResult.Valid
        } else {
            SectionValidationResult.Invalid(errors)
        }
    }

    private fun validateS34(
        sections: List<SpeechSection>,
        errors: MutableList<SectionValidationError>,
    ) {
        val intros = sections.filter { it.role == SectionRole.INTRO }
        val conclusions = sections.filter { it.role == SectionRole.CONCLUSION }
        val bodies = sections.filter { it.role == SectionRole.BODY }

        if (intros.size > 1) errors.add(SectionValidationError.MULTIPLE_INTRO)
        if (conclusions.size > 1) errors.add(SectionValidationError.MULTIPLE_CONCLUSION)
        if (bodies.isEmpty()) errors.add(SectionValidationError.NO_BODY)

        if (sections.any { it.minutes <= 0 }) {
            errors.add(SectionValidationError.NON_POSITIVE_MINUTES)
        }

        if (intros.isNotEmpty() && intros.first().order != 0) {
            errors.add(SectionValidationError.INTRO_NOT_FIRST)
        }
        if (conclusions.isNotEmpty() && conclusions.first().order != sections.size - 1) {
            errors.add(SectionValidationError.CONCLUSION_NOT_LAST)
        }
    }

    private fun validateSingleBody(
        sections: List<SpeechSection>,
        errors: MutableList<SectionValidationError>,
    ) {
        if (sections.any { it.role == SectionRole.INTRO }) {
            errors.add(SectionValidationError.UNEXPECTED_INTRO)
        }
        if (sections.any { it.role == SectionRole.CONCLUSION }) {
            errors.add(SectionValidationError.UNEXPECTED_CONCLUSION)
        }
        if (sections.count { it.role == SectionRole.BODY } != 1) {
            errors.add(SectionValidationError.WRONG_BODY_COUNT)
        }
        if (sections.any { it.minutes <= 0 }) {
            errors.add(SectionValidationError.NON_POSITIVE_MINUTES)
        }
    }
}
