package com.bettertalker.app.domain.speech

import com.bettertalker.app.domain.planning.PublicationRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSectionValidatorTest {

    private fun section(
        id: String,
        role: SectionRole,
        order: Int,
        minutes: Int = 5,
    ) = SpeechSection(
        id = id, noteId = "n1", order = order, role = role,
        title = "Título $id", minutes = minutes, contentHtml = "<p>x</p>",
        bibleRefs = listOf("Jo 3:16"), publicationRefs = emptyList(),
        methodPrinciple = null, createdAt = 1L, updatedAt = 2L,
    )

    private fun valid() = listOf(
        section("i", SectionRole.INTRO, 0, minutes = 2),
        section("b", SectionRole.BODY, 1),
        section("c", SectionRole.CONCLUSION, 2, minutes = 3),
    )

    @Test
    fun validWithOneIntroOneBodyOneConclusion_returnsValid() {
        assertEquals(SectionValidationResult.Valid, SpeechSectionValidator.validate(valid()))
    }

    @Test
    fun emptyList_returnsNoSections() {
        assertEquals(
            SectionValidationResult.Invalid(listOf(SectionValidationError.NO_SECTIONS)),
            SpeechSectionValidator.validate(emptyList()),
        )
    }

    @Test
    fun twoIntros_returnsMultipleIntro() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("i1", SectionRole.INTRO, 0),
                section("i2", SectionRole.INTRO, 1),
                section("b", SectionRole.BODY, 2),
            )
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.MULTIPLE_INTRO))
    }

    @Test
    fun twoConclusions_returnsMultipleConclusion() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("b", SectionRole.BODY, 0),
                section("c1", SectionRole.CONCLUSION, 1),
                section("c2", SectionRole.CONCLUSION, 2),
            )
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.MULTIPLE_CONCLUSION))
    }

    @Test
    fun noBody_returnsNoBody() {
        val errors = (SpeechSectionValidator.validate(
            listOf(section("i", SectionRole.INTRO, 0))
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.NO_BODY))
    }

    @Test
    fun zeroMinutes_returnsNonPositiveMinutes() {
        val errors = (SpeechSectionValidator.validate(
            listOf(section("b", SectionRole.BODY, 0, minutes = 0))
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.NON_POSITIVE_MINUTES))
    }

    @Test
    fun negativeMinutes_returnsNonPositiveMinutes() {
        val errors = (SpeechSectionValidator.validate(
            listOf(section("b", SectionRole.BODY, 0, minutes = -3))
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.NON_POSITIVE_MINUTES))
    }

    @Test
    fun introNotFirst_returnsIntroNotFirst() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("b", SectionRole.BODY, 0),
                section("i", SectionRole.INTRO, 1),
            )
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.INTRO_NOT_FIRST))
    }

    @Test
    fun conclusionNotLast_returnsConclusionNotLast() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("c", SectionRole.CONCLUSION, 0),
                section("b", SectionRole.BODY, 1),
            )
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.CONCLUSION_NOT_LAST))
    }

    @Test
    fun multipleErrors_returnsAllInOrder() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("b", SectionRole.BODY, 0, minutes = 0),
                section("i1", SectionRole.INTRO, 1),
                section("i2", SectionRole.INTRO, 2),
            )
        ) as SectionValidationResult.Invalid).errors
        assertEquals(
            listOf(
                SectionValidationError.MULTIPLE_INTRO,
                SectionValidationError.NON_POSITIVE_MINUTES,
                SectionValidationError.INTRO_NOT_FIRST,
            ),
            errors,
        )
    }

    // PublicationRef lives in domain/planning — smoke usage to pin the import.
    @Test
    fun publicationRef_importResolves() {
        val ref = PublicationRef("w21.08", page = 18, paragraph = 13)
        assertEquals("w21.08", ref.symbol)
    }

    // ---------- 3.2.4a: validação condicional por tipo ----------

    @Test
    fun s34_validStructure_returnsValid() {
        assertEquals(
            SectionValidationResult.Valid,
            SpeechSectionValidator.validate(valid(), DiscourseType.S34_DISCOURSE),
        )
    }

    @Test
    fun s34_missingIntro_isStillValid() {
        // Regressão: ausência de INTRO nunca foi erro no S-34 (só MULTIPLE_INTRO
        // é); o default do parâmetro preserva esse comportamento.
        assertEquals(
            SectionValidationResult.Valid,
            SpeechSectionValidator.validate(
                listOf(section("b", SectionRole.BODY, 0)),
                DiscourseType.S34_DISCOURSE,
            ),
        )
    }

    @Test
    fun treasures_validStructure_returnsValid() {
        assertEquals(
            SectionValidationResult.Valid,
            SpeechSectionValidator.validate(
                listOf(section("b", SectionRole.BODY, 0)),
                DiscourseType.TREASURES_TALK,
            ),
        )
    }

    @Test
    fun treasures_withIntro_returnsUnexpectedIntro() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("i", SectionRole.INTRO, 0),
                section("b", SectionRole.BODY, 1),
            ),
            DiscourseType.TREASURES_TALK,
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.UNEXPECTED_INTRO))
    }

    @Test
    fun treasures_withConclusion_returnsUnexpectedConclusion() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("b", SectionRole.BODY, 0),
                section("c", SectionRole.CONCLUSION, 1),
            ),
            DiscourseType.TREASURES_TALK,
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.UNEXPECTED_CONCLUSION))
    }

    @Test
    fun treasures_twoBodies_returnsWrongBodyCount() {
        val errors = (SpeechSectionValidator.validate(
            listOf(
                section("b1", SectionRole.BODY, 0),
                section("b2", SectionRole.BODY, 1),
            ),
            DiscourseType.TREASURES_TALK,
        ) as SectionValidationResult.Invalid).errors
        assertEquals(true, errors.contains(SectionValidationError.WRONG_BODY_COUNT))
    }

    @Test
    fun ministry_validStructure_returnsValid() {
        assertEquals(
            SectionValidationResult.Valid,
            SpeechSectionValidator.validate(
                listOf(section("b", SectionRole.BODY, 0)),
                DiscourseType.MINISTRY_PART,
            ),
        )
    }

    @Test
    fun avulso_validStructure_returnsValid() {
        assertEquals(
            SectionValidationResult.Valid,
            SpeechSectionValidator.validate(
                listOf(section("b", SectionRole.BODY, 0)),
                DiscourseType.AVULSO,
            ),
        )
    }
}
