package com.bettertalker.app.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineProposalValidatorTest {

    private fun validProposal() = OutlineProposal(
        id = "test-1",
        angle = OutlineAngle.DOCTRINAL,
        audience = Audience.GENERAL,
        title = "A esperança da ressurreição",
        summary = "Discurso sobre a promessa bíblica da ressurreição.",
        totalMinutes = 15,
        sections = listOf(
            SectionPlan(
                title = "INTRODUÇÃO",
                minutes = 3,
                mainIdea = "Gancho sobre a finitude da vida",
                bibleRefs = listOf("Jó 14:1,2"),
            ),
            SectionPlan(
                title = "A PROMESSA BÍBLICA",
                minutes = 7,
                mainIdea = "Textos que prometem ressurreição",
                bibleRefs = listOf("João 5:28,29", "Atos 24:15"),
            ),
            SectionPlan(
                title = "CONCLUSÃO",
                minutes = 5,
                mainIdea = "Convidar a confiar na promessa",
                bibleRefs = listOf("Apo 21:4"),
            ),
        ),
    )

    private fun errorsOf(proposal: OutlineProposal): List<ValidationError> {
        val result = OutlineProposalValidator.validate(proposal)
        assertTrue(result is ValidationResult.Invalid)
        return (result as ValidationResult.Invalid).errors
    }

    @Test
    fun validProposal_withNoErrors_returnsValid() {
        assertEquals(ValidationResult.Valid, OutlineProposalValidator.validate(validProposal()))
    }

    @Test
    fun blankTitle_returnsInvalidWithBlankTitle() {
        val errors = errorsOf(validProposal().copy(title = "   "))
        assertTrue(errors.contains(ValidationError.BLANK_TITLE))
    }

    @Test
    fun blankSummary_returnsInvalidWithBlankSummary() {
        val errors = errorsOf(validProposal().copy(summary = ""))
        assertTrue(errors.contains(ValidationError.BLANK_SUMMARY))
    }

    @Test
    fun nonPositiveTotalMinutes_returnsInvalid() {
        val errors = errorsOf(validProposal().copy(totalMinutes = 0))
        assertTrue(errors.contains(ValidationError.NON_POSITIVE_TOTAL_MINUTES))
    }

    @Test
    fun emptySections_returnsInvalidWithNoSections() {
        val errors = errorsOf(validProposal().copy(sections = emptyList()))
        assertTrue(errors.contains(ValidationError.NO_SECTIONS))
    }

    @Test
    fun blankSectionTitle_returnsInvalidWithSectionBlankTitle() {
        val proposal = validProposal().copy(
            sections = listOf(
                SectionPlan(title = "  ", minutes = 3, mainIdea = "Ideia válida", bibleRefs = listOf("Jó 14:1")),
            ),
        )
        val errors = errorsOf(proposal)
        assertTrue(errors.contains(ValidationError.SECTION_BLANK_TITLE))
    }

    @Test
    fun sectionNonPositiveMinutes_returnsInvalid() {
        val proposal = validProposal().copy(
            sections = listOf(
                SectionPlan(title = "INTRODUÇÃO", minutes = 0, mainIdea = "Ideia válida", bibleRefs = listOf("Jó 14:1")),
            ),
        )
        val errors = errorsOf(proposal)
        assertTrue(errors.contains(ValidationError.SECTION_NON_POSITIVE_MINUTES))
    }

    @Test
    fun blankSectionMainIdea_returnsInvalid() {
        val proposal = validProposal().copy(
            sections = listOf(
                SectionPlan(title = "INTRODUÇÃO", minutes = 3, mainIdea = "   ", bibleRefs = listOf("Jó 14:1")),
            ),
        )
        val errors = errorsOf(proposal)
        assertTrue(errors.contains(ValidationError.SECTION_BLANK_MAIN_IDEA))
    }

    @Test
    fun duplicateSectionTitles_returnsInvalid() {
        val proposal = validProposal().copy(
            sections = listOf(
                SectionPlan(title = "INTRODUÇÃO", minutes = 3, mainIdea = "Ideia um", bibleRefs = listOf("Jó 14:1")),
                SectionPlan(title = "introdução", minutes = 3, mainIdea = "Ideia dois", bibleRefs = listOf("Sal 23:1")),
            ),
        )
        val errors = errorsOf(proposal)
        assertTrue(errors.contains(ValidationError.DUPLICATE_SECTION_TITLES))
    }

    @Test
    fun noSectionWithBibleRefs_returnsInvalid() {
        val proposal = validProposal().copy(
            sections = listOf(
                SectionPlan(title = "INTRODUÇÃO", minutes = 3, mainIdea = "Ideia um"),
                SectionPlan(title = "CONCLUSÃO", minutes = 5, mainIdea = "Ideia dois"),
            ),
        )
        val errors = errorsOf(proposal)
        assertTrue(errors.contains(ValidationError.NO_SECTION_WITH_BIBLE_REFS))
    }

    @Test
    fun sectionMinutesSumExceedsTotal_returnsInvalid() {
        val proposal = validProposal().copy(totalMinutes = 5)
        val errors = errorsOf(proposal)
        assertTrue(errors.contains(ValidationError.SECTION_MINUTES_SUM_MISMATCH))
    }

    @Test
    fun sectionMinutesSumLessThanTotal_isValid() {
        val proposal = validProposal().copy(
            totalMinutes = 15,
            sections = listOf(
                SectionPlan(title = "INTRODUÇÃO", minutes = 3, mainIdea = "Ideia um", bibleRefs = listOf("Jó 14:1")),
                SectionPlan(title = "CONCLUSÃO", minutes = 5, mainIdea = "Ideia dois", bibleRefs = listOf("Apo 21:4")),
            ),
        )
        assertEquals(ValidationResult.Valid, OutlineProposalValidator.validate(proposal))
    }

    @Test
    fun multipleErrors_returnsAllErrors() {
        val proposal = validProposal().copy(title = "", summary = "x", totalMinutes = -5, sections = emptyList())
        val errors = errorsOf(proposal)
        assertEquals(
            listOf(
                ValidationError.BLANK_TITLE,
                ValidationError.NON_POSITIVE_TOTAL_MINUTES,
                ValidationError.NO_SECTIONS,
            ),
            errors,
        )
    }

    @Test
    fun errorListOrderMatchesSpec() {
        val proposal = validProposal().copy(
            title = "  ",
            summary = "",
            totalMinutes = -3,
            sections = listOf(
                SectionPlan(title = "  ", minutes = 2, mainIdea = "Ideia válida"),
                SectionPlan(title = "FOCO", minutes = 0, mainIdea = "   "),
                SectionPlan(title = "foco", minutes = -2, mainIdea = ""),
            ),
        )
        val errors = errorsOf(proposal)
        assertEquals(
            listOf(
                ValidationError.BLANK_TITLE,
                ValidationError.BLANK_SUMMARY,
                ValidationError.NON_POSITIVE_TOTAL_MINUTES,
                ValidationError.SECTION_BLANK_TITLE,
                ValidationError.SECTION_NON_POSITIVE_MINUTES,
                ValidationError.SECTION_BLANK_MAIN_IDEA,
                ValidationError.DUPLICATE_SECTION_TITLES,
                ValidationError.NO_SECTION_WITH_BIBLE_REFS,
                ValidationError.SECTION_MINUTES_SUM_MISMATCH,
            ),
            errors,
        )
    }
}
