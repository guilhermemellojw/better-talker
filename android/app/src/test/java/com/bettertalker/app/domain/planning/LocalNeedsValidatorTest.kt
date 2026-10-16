package com.bettertalker.app.domain.planning

import org.junit.Assert.assertTrue
import org.junit.Test

/** #8f — validador com a regra de refs relaxada para Necessidades Locais. Puro. */
class LocalNeedsValidatorTest {

    private fun proposal() = OutlineProposal(
        id = "p1",
        angle = OutlineAngle.PRACTICAL,
        audience = Audience.GENERAL,
        title = "T",
        summary = "S",
        totalMinutes = 5,
        sections = listOf(SectionPlan(title = "A", minutes = 5, mainIdea = "I")),
    )

    @Test
    fun refsVaziasComAllowEmptyEhValida() {
        val r = OutlineProposalValidator.validate(proposal(), allowEmptyRefs = true)
        assertTrue(r is ValidationResult.Valid)
    }

    @Test
    fun refsVaziasSemAllowEmptyEhInvalida() {
        val r = OutlineProposalValidator.validate(proposal())
        assertTrue(r is ValidationResult.Invalid)
        assertTrue(
            (r as ValidationResult.Invalid).errors.contains(
                ValidationError.NO_SECTION_WITH_BIBLE_REFS
            )
        )
    }
}
